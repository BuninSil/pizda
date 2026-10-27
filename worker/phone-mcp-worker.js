/**
 * Phone MCP — relay Worker (Durable Object).
 *
 * Replaces the old cloudflared Quick Tunnel. The phone opens ONE outbound
 * WebSocket to this Worker over plain 443 (wss://<worker>/<token>/__phone), so
 * providers and VPNs that kill cloudflared's :7844 edge can't break it. Claude's
 * MCP requests to https://<worker>/<token>/mcp are routed to a Durable Object
 * (one instance per token), packed into JSON, pushed to the phone over that
 * socket, and the phone's reply is streamed straight back.
 *
 * Setup (no domain needed):
 *   cd worker
 *   npm i -g wrangler
 *   wrangler login
 *   wrangler secret put UPDATE_SECRET      # same value baked into the app (RELAY_SECRET)
 *   wrangler deploy
 *
 * Connector URL (paste into Claude once), where <token> is shown in the app:
 *   https://<name>.<you>.workers.dev/<token>/mcp
 */

const RESPONSE_TIMEOUT_MS = 60_000;

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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    if (parts.length === 0) {
      return new Response("phone-mcp relay ok", { status: 200 });
    }
    // First path segment is the secret token. Everything for a token goes to the
    // same Durable Object instance, so only the instance whose phone is connected
    // can answer — a wrong token lands on an empty instance and gets 404.
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
    // id -> { resolve, timer, writer, started }
    this.pending = new Map();
    this.everConnected = false;
    this.lastPing = 0;
    ctx.blockConcurrencyWhile(async () => {
      this.everConnected = (await ctx.storage.get("everConnected")) || false;
      this.lastPing = (await ctx.storage.get("lastPing")) || 0;
    });
  }

  phoneSocket() {
    const socks = this.ctx.getWebSockets("phone");
    return socks.length ? socks[0] : null;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    const rest = parts.slice(1).join("/"); // path after the token

    if (rest === "__phone") return this.handlePhoneConnect(request);
    if (rest === "__status") return this.handleStatus();

    // A request from Claude — relay it to the phone.
    const sock = this.phoneSocket();
    if (!sock) {
      if (!this.everConnected) {
        // Unknown token (no phone ever registered on this instance). 404, NOT 401:
        // claude.ai treats 401 as an OAuth requirement and aborts the connect.
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
    return this.relay(request, sock, url.pathname, url.search);
  }

  async handlePhoneConnect(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    // Authenticate the phone. The secret is optional only if UPDATE_SECRET is unset.
    if (this.env.UPDATE_SECRET) {
      const given = request.headers.get("x-update-secret") || "";
      if (given !== this.env.UPDATE_SECRET) {
        return new Response("forbidden", { status: 403 });
      }
    }
    const pair = new WebSocketPair();
    // Hibernation API: the runtime can evict us between messages and still deliver
    // them to webSocketMessage/Close. Tag the socket so getWebSockets("phone") finds it.
    this.ctx.acceptWebSocket(pair[1], ["phone"]);
    if (!this.everConnected) {
      this.everConnected = true;
      await this.ctx.storage.put("everConnected", true);
    }
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  handleStatus() {
    const connected = !!this.phoneSocket();
    const body = {
      connected,
      everConnected: this.everConnected,
      lastPingMs: this.lastPing || null,
      lastPingAgoSec: this.lastPing ? Math.round((Date.now() - this.lastPing) / 1000) : null,
    };
    return new Response(JSON.stringify(body), {
      headers: { "content-type": "application/json; charset=utf-8" },
    });
  }

  async relay(request, sock, pathname, search) {
    const id = crypto.randomUUID();

    const headers = {};
    for (const [k, v] of request.headers) {
      const lk = k.toLowerCase();
      if (lk === "host" || lk === "connection" || lk === "x-update-secret") continue;
      headers[lk] = v;
    }

    let bodyB64 = "";
    if (request.method !== "GET" && request.method !== "HEAD") {
      const buf = await request.arrayBuffer();
      if (buf.byteLength) bodyB64 = bytesToB64(buf);
    }

    const frame = {
      id,
      method: request.method,
      // Full path INCLUDING the token, so the phone re-validates the token itself.
      path: pathname + (search || ""),
      headers,
      bodyB64,
    };

    const responsePromise = new Promise((resolve) => {
      const timer = setTimeout(() => {
        const p = this.pending.get(id);
        if (p) {
          this.pending.delete(id);
          if (p.writer) { try { p.writer.close(); } catch (_) {} }
          resolve(new Response("phone timed out", { status: 504 }));
        }
      }, RESPONSE_TIMEOUT_MS);
      this.pending.set(id, { resolve, timer, writer: null, started: false });
    });

    try {
      sock.send(JSON.stringify(frame));
    } catch (e) {
      const p = this.pending.get(id);
      if (p) { clearTimeout(p.timer); this.pending.delete(id); }
      return new Response("failed to reach phone: " + e, { status: 502 });
    }

    return responsePromise;
  }

  // --- Hibernation socket handlers (phone -> worker) --------------------

  async webSocketMessage(ws, data) {
    let msg;
    try {
      msg = JSON.parse(typeof data === "string" ? data : new TextDecoder().decode(data));
    } catch (_) {
      return;
    }

    if (msg.type === "ping") {
      this.lastPing = Date.now();
      await this.ctx.storage.put("lastPing", this.lastPing);
      try { ws.send(JSON.stringify({ type: "pong" })); } catch (_) {}
      return;
    }

    const id = msg.id;
    if (!id) return;
    const p = this.pending.get(id);
    if (!p) return;

    // Streaming reply (e.g. SSE): first frame opens a stream, then chunks, then end.
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

    // Complete (non-streamed) reply.
    clearTimeout(p.timer);
    this.pending.delete(id);
    const bytes = msg.bodyB64 ? b64ToBytes(msg.bodyB64) : null;
    p.resolve(new Response(bytes, { status: msg.status || 200, headers: msg.headers || {} }));
  }

  webSocketClose(ws, code, reason, wasClean) {
    // The phone dropped; outstanding requests will hit their 60s timeout.
  }

  webSocketError(ws, error) {
    // Same as close — let pending requests time out.
  }
}
