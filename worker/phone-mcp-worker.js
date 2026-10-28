/**
 * Phone MCP — relay Worker (Durable Object), multi-device.
 *
 * The phone (and, optionally, a laptop) each open ONE outbound WebSocket to this
 * Worker over plain 443:
 *   phone:  wss://<worker>/<token>/__phone
 *   laptop: wss://<worker>/<token>/__laptop
 * both with header x-update-secret. Claude's MCP requests hit
 *   https://<worker>/<token>/mcp
 * and the Durable Object (one instance per token) relays them to a device.
 *
 * Routing:
 *   - initialize / ping / sessions / notifications  -> phone (authoritative)
 *   - tools/list   -> query BOTH devices, merge (laptop tools are named laptop_*,
 *                     and only included when the laptop is online AND enabled)
 *   - tools/call   -> name starts with "laptop_" -> laptop, else -> phone
 *   - GET/DELETE/file -> phone
 *
 * A device speaks a tiny frame protocol over its socket:
 *   worker -> device : {id, method, path, headers, bodyB64}
 *   device -> worker : {id, status, headers, bodyB64}   (or stream: {id,stream}/{id,chunkB64}/{id,end})
 *   device -> worker : {type:"ping"}  (keepalive; worker replies {type:"pong"})
 *
 * Control:
 *   POST /<token>/__laptop_toggle  {enabled:true|false}  (x-update-secret)
 *   GET  /<token>/__status         status of both devices + the toggle
 *
 * Setup:
 *   cd worker && wrangler login && wrangler deploy
 *   (optional secret) wrangler secret put UPDATE_SECRET  == app RELAY_SECRET
 */

const RESPONSE_TIMEOUT_MS = 60_000;
const LIST_TIMEOUT_MS = 5_000; // internal tools/list fan-out to a device
// Exact keepalive strings. Both devices MUST send this literal so the runtime's
// auto-response matches it and answers WITHOUT waking the Durable Object.
const PING = '{"type":"ping"}';
const PONG = '{"type":"pong"}';

function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function bytesToB64(bytes) {
  let bin = "";
  const arr = new Uint8Array(bytes);
  const CHUNK = 0x8000;
  for (let i = 0; i < arr.length; i += CHUNK) {
    bin += String.fromCharCode.apply(null, arr.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

function jsonResponse(obj, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...extraHeaders },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    if (parts.length === 0) {
      return new Response("phone-mcp relay ok", { status: 200 });
    }
    const token = parts[0];
    const id = env.PHONE_RELAY.idFromName(token);
    const stub = env.PHONE_RELAY.get(id);
    return stub.fetch(request);
  },
};

export class PhoneRelay {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.pending = new Map(); // id -> { resolve, timer, writer, started }
    this.everConnected = false; // a phone has connected at least once
    this.laptopEnabled = true; // laptop tools exposed when online (toggle hides them)
    // Keepalive pings are answered by the runtime itself, so a 20s ping never
    // wakes a hibernating DO (online is read from getWebSockets, not from pings).
    try {
      ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair(PING, PONG));
    } catch (_) {}
    ctx.blockConcurrencyWhile(async () => {
      this.everConnected = (await ctx.storage.get("everConnected")) || false;
      const le = await ctx.storage.get("laptopEnabled");
      this.laptopEnabled = le === undefined ? true : le;
    });
  }

  // Online is derived ONLY from the live hibernatable sockets — no in-memory
  // flags, so it survives the DO sleeping and waking.
  socket(tag) {
    const socks = this.ctx.getWebSockets(tag);
    return socks.length ? socks[0] : null;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    const token = parts[0];
    const rest = parts.slice(1).join("/");

    if (rest === "__phone") return this.handleDeviceConnect(request, "phone");
    if (rest === "__laptop") return this.handleDeviceConnect(request, "laptop");
    if (rest === "__laptop_toggle") return this.handleLaptopToggle(request);
    if (rest === "__status") return this.handleStatus();

    // Everything else is an MCP request from Claude. The phone is the primary
    // device: without it there is no session/initialize, so gate on it.
    const phone = this.socket("phone");
    if (!phone) {
      if (!this.everConnected) {
        return new Response(
          "unknown path/token — not an OAuth-protected resource. Check the connector URL.",
          { status: 404, headers: { "content-type": "text/plain; charset=utf-8" } },
        );
      }
      return new Response(
        "phone offline — телефон не на связи с релеем. Открой приложение «Руки» и включи сервер.",
        { status: 503, headers: { "content-type": "text/plain; charset=utf-8" } },
      );
    }

    const headers = this.headerObj(request);

    // Intercept MCP POSTs so we can route/merge across devices.
    if (request.method === "POST" && rest === "mcp") {
      const bodyBytes = new Uint8Array(await request.arrayBuffer());
      let rpc = null;
      try { rpc = JSON.parse(new TextDecoder().decode(bodyBytes)); } catch (_) {}

      if (rpc && !Array.isArray(rpc) && typeof rpc === "object") {
        if (rpc.method === "tools/list") {
          return this.handleToolsList(url.pathname, headers, bodyBytes);
        }
        const name = rpc.method === "tools/call" ? (rpc.params && rpc.params.name) || "" : "";
        if (rpc.method === "tools/call" && name.startsWith("laptop_")) {
          return this.handleLaptopCall(url.pathname, headers, bodyBytes, rpc);
        }
      }
      // Default: straight to the phone.
      return this.forwardFrame(phone, request.method, url.pathname + url.search, headers, bodyBytes);
    }

    // GET (SSE) / DELETE / file — forward to the phone unchanged.
    return this.forwardFrame(phone, request.method, url.pathname + url.search, headers, null);
  }

  headerObj(request) {
    const headers = {};
    for (const [k, v] of request.headers) {
      const lk = k.toLowerCase();
      if (lk === "host" || lk === "connection" || lk === "x-update-secret") continue;
      headers[lk] = v;
    }
    return headers;
  }

  // --- tools/list fan-out + merge --------------------------------------

  async handleToolsList(pathname, headers, bodyBytes) {
    const phone = this.socket("phone");
    const phoneResp = await this.forwardFrame(phone, "POST", pathname, headers, bodyBytes);
    const phoneText = await phoneResp.text();
    let phoneJson;
    try { phoneJson = JSON.parse(phoneText); } catch (_) {
      return new Response(phoneText, { status: phoneResp.status, headers: phoneResp.headers });
    }

    // Add the laptop's tools when it's online AND enabled.
    const laptop = this.socket("laptop");
    const phoneCount = phoneJson && phoneJson.result && Array.isArray(phoneJson.result.tools)
      ? phoneJson.result.tools.length : -1;
    if (!this.laptopEnabled) {
      console.log("tools/list: laptop skipped (toggle off), phone tools=" + phoneCount);
    } else if (!laptop) {
      console.log("tools/list: laptop skipped (offline), phone tools=" + phoneCount);
    } else if (phoneCount < 0) {
      console.log("tools/list: phone reply not a tools result, passing through");
    } else {
      const lapObj = await this.queryDevice("laptop", pathname, { jsonrpc: "2.0", id: "ll", method: "tools/list" }, LIST_TIMEOUT_MS);
      const lapTools = lapObj && lapObj.result && Array.isArray(lapObj.result.tools) ? lapObj.result.tools : [];
      if (!lapObj) {
        console.log("tools/list: laptop did NOT answer in " + LIST_TIMEOUT_MS + "ms — phone-only");
      } else {
        const have = new Set(phoneJson.result.tools.map((t) => t.name));
        let added = 0;
        for (const t of lapTools) if (!have.has(t.name)) { phoneJson.result.tools.push(t); added++; }
        console.log("tools/list: merged phone=" + phoneCount + " + laptop=" + added);
      }
    }

    const outHeaders = {};
    for (const [k, v] of phoneResp.headers) outHeaders[k] = v;
    return new Response(JSON.stringify(phoneJson), { status: 200, headers: outHeaders });
  }

  async handleLaptopCall(pathname, headers, bodyBytes, rpc) {
    const laptop = this.socket("laptop");
    const name = (rpc.params && rpc.params.name) || "?";
    console.log("route laptop_call " + name + " enabled=" + this.laptopEnabled + " online=" + !!laptop);
    if (!this.laptopEnabled || !laptop) {
      // A proper MCP tool error (result with isError), so Claude surfaces it cleanly.
      return jsonResponse({
        jsonrpc: "2.0",
        id: rpc.id ?? null,
        result: { content: [{ type: "text", text: "laptop offline — ноут не подключён или выключен тумблером" }], isError: true },
      });
    }
    return this.forwardFrame(laptop, "POST", pathname, headers, bodyBytes);
  }

  /** Send a synthesized JSON-RPC request to a device and parse its JSON reply. */
  async queryDevice(tag, pathname, bodyObj, timeoutMs) {
    const sock = this.socket(tag);
    if (!sock) return null;
    const bytes = new TextEncoder().encode(JSON.stringify(bodyObj));
    const resp = await this.forwardFrame(sock, "POST", pathname, { "content-type": "application/json" }, bytes, timeoutMs);
    try { return JSON.parse(await resp.text()); } catch (_) { return null; }
  }

  // --- device connect / toggle / status --------------------------------

  async handleDeviceConnect(request, tag) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    if (this.env.UPDATE_SECRET) {
      const given = request.headers.get("x-update-secret") || "";
      if (given !== this.env.UPDATE_SECRET) {
        console.log("connect rejected: bad secret, tag=" + tag);
        return new Response("forbidden", { status: 403 });
      }
    }
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], [tag]);
    // Per-socket metadata survives hibernation via the attachment, not memory.
    try { pair[1].serializeAttachment({ tag, at: Date.now() }); } catch (_) {}
    if (tag === "phone" && !this.everConnected) {
      this.everConnected = true;
      await this.ctx.storage.put("everConnected", true);
    }
    console.log("connect: tag=" + tag + " now phone=" + !!this.socket("phone") + " laptop=" + !!this.socket("laptop"));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async handleLaptopToggle(request) {
    if (request.method !== "POST") return new Response("POST only", { status: 405 });
    if (this.env.UPDATE_SECRET) {
      const given = request.headers.get("x-update-secret") || "";
      if (given !== this.env.UPDATE_SECRET) return new Response("forbidden", { status: 403 });
    }
    let body;
    try { body = await request.json(); } catch (_) { return new Response("bad json", { status: 400 }); }
    this.laptopEnabled = !!body.enabled;
    await this.ctx.storage.put("laptopEnabled", this.laptopEnabled);
    // The set of tools changed; if we held an open SSE stream we'd push
    // notifications/tools/list_changed here. We don't keep one, so Claude picks
    // the new list up on its next tools/list (reconnect/refresh).
    return jsonResponse({ ok: true, laptopEnabled: this.laptopEnabled });
  }

  handleStatus() {
    const phone = !!this.socket("phone");
    const laptop = !!this.socket("laptop");
    return jsonResponse({
      // Flat bools (from getWebSockets + storage), plus nested for the app.
      phone_connected: phone,
      laptop_connected: laptop,
      laptop_enabled: this.laptopEnabled,
      phone: { connected: phone },
      laptop: { connected: laptop, enabled: this.laptopEnabled },
      everConnected: this.everConnected,
    });
  }

  // --- frame transport -------------------------------------------------

  async forwardFrame(sock, method, pathWithSearch, headers, bodyBytes, timeoutMs = RESPONSE_TIMEOUT_MS) {
    const id = crypto.randomUUID();
    const frame = {
      id,
      method,
      path: pathWithSearch, // includes the token, so the device re-validates it
      headers,
      bodyB64: bodyBytes && bodyBytes.byteLength ? bytesToB64(bodyBytes) : "",
    };

    const responsePromise = new Promise((resolve) => {
      const timer = setTimeout(() => {
        const p = this.pending.get(id);
        if (p) {
          this.pending.delete(id);
          if (p.writer) { try { p.writer.close(); } catch (_) {} }
          resolve(new Response("device timed out", { status: 504 }));
        }
      }, timeoutMs);
      this.pending.set(id, { resolve, timer, writer: null, started: false });
    });

    try {
      sock.send(JSON.stringify(frame));
    } catch (e) {
      const p = this.pending.get(id);
      if (p) { clearTimeout(p.timer); this.pending.delete(id); }
      return new Response("failed to reach device: " + e, { status: 502 });
    }
    return responsePromise;
  }

  // --- Hibernation socket handlers (device -> worker) ------------------

  async webSocketMessage(ws, data) {
    let msg;
    try {
      msg = JSON.parse(typeof data === "string" ? data : new TextDecoder().decode(data));
    } catch (_) {
      return;
    }

    // Pings are handled by setWebSocketAutoResponse and never reach here. If an
    // old client still sends one through this path, just answer and don't wake further.
    if (msg.type === "ping") {
      try { ws.send(PONG); } catch (_) {}
      return;
    }

    const id = msg.id;
    if (!id) return;
    const p = this.pending.get(id);
    if (!p) return;

    if (msg.stream && !p.started) {
      p.started = true;
      const { readable, writable } = new TransformStream();
      p.writer = writable.getWriter();
      clearTimeout(p.timer);
      p.resolve(new Response(readable, { status: msg.status || 200, headers: msg.headers || {} }));
      return;
    }
    if (msg.chunkB64 !== undefined) {
      if (p.writer) { try { await p.writer.write(b64ToBytes(msg.chunkB64)); } catch (_) {} }
      return;
    }
    if (msg.end) {
      if (p.writer) { try { await p.writer.close(); } catch (_) {} }
      this.pending.delete(id);
      return;
    }

    clearTimeout(p.timer);
    this.pending.delete(id);
    const bytes = msg.bodyB64 ? b64ToBytes(msg.bodyB64) : null;
    p.resolve(new Response(bytes, { status: msg.status || 200, headers: msg.headers || {} }));
  }

  webSocketClose(ws, code, reason, wasClean) {
    // Only this socket is gone; getWebSockets() stops returning it on its own, so
    // there is no per-socket state to clean. Outstanding requests time out.
    let tag = "?";
    try { tag = (ws.deserializeAttachment() || {}).tag || "?"; } catch (_) {}
    console.log("close: tag=" + tag + " code=" + code + " clean=" + wasClean +
      " now phone=" + !!this.socket("phone") + " laptop=" + !!this.socket("laptop"));
  }

  webSocketError(ws, error) {
    let tag = "?";
    try { tag = (ws.deserializeAttachment() || {}).tag || "?"; } catch (_) {}
    console.log("error: tag=" + tag + " " + error);
  }
}
