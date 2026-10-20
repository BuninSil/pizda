/**
 * Phone MCP — stable-URL proxy Worker.
 *
 * Gives you ONE permanent URL (https://<name>.<you>.workers.dev) that forwards
 * to whatever Cloudflare Quick Tunnel the phone currently has. The phone app
 * pushes its current tunnel URL here on every (re)connect, so the public address
 * never changes and you connect the Claude connector only once.
 *
 * Setup (no domain needed):
 *   1. Cloudflare dashboard → Workers & Pages → Create → Worker. Paste this code, Deploy.
 *   2. Workers & Pages → KV → Create namespace (e.g. "phone-mcp"). Open your Worker →
 *      Settings → Variables → KV Namespace Bindings → add binding: Variable name = KV,
 *      your namespace.
 *   3. Same Settings → Variables → add a Secret: Name = UPDATE_SECRET, value = a long
 *      random string. Put the SAME string into the app (Постоянный адрес → секрет).
 *   4. In the app, Постоянный адрес: Worker URL = https://<name>.<you>.workers.dev,
 *      and the secret from step 3. Turn the server on.
 *
 * Your connector URL (paste into Claude once):
 *   https://<name>.<you>.workers.dev/<token>/mcp
 * where <token> is the one shown on the app's main screen.
 */

const HOP_BY_HOP = new Set([
  "host", "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
  "te", "trailer", "transfer-encoding", "upgrade",
]);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // --- Control: update the current backend tunnel URL ---
    if (url.pathname === "/__set") {
      if (request.method !== "POST") return new Response("POST only", { status: 405 });
      const secret = request.headers.get("x-update-secret") || "";
      if (!env.UPDATE_SECRET || secret !== env.UPDATE_SECRET) {
        return new Response("forbidden", { status: 403 });
      }
      let body;
      try { body = await request.json(); } catch { return new Response("bad json", { status: 400 }); }
      const backend = String(body.url || "").trim().replace(/\/+$/, "");
      if (!/^https:\/\/[a-z0-9.-]+$/i.test(backend)) {
        return new Response("bad url", { status: 400 });
      }
      await env.KV.put("backend", backend);
      return new Response("ok " + backend);
    }

    if (url.pathname === "/__status") {
      const b = await env.KV.get("backend");
      return new Response(JSON.stringify({ backend: b || null }), {
        headers: { "content-type": "application/json" },
      });
    }

    // --- Proxy everything else to the current backend ---
    const backend = await env.KV.get("backend");
    if (!backend) return new Response("backend not set yet", { status: 503 });

    const target = backend + url.pathname + url.search;
    const headers = new Headers();
    for (const [k, v] of request.headers) {
      if (!HOP_BY_HOP.has(k.toLowerCase())) headers.set(k, v);
    }

    const hasBody = request.method !== "GET" && request.method !== "HEAD";
    const init = {
      method: request.method,
      headers,
      body: hasBody ? request.body : undefined,
      redirect: "manual",
    };
    if (hasBody) init.duplex = "half"; // required when streaming a request body

    let resp;
    try {
      resp = await fetch(target, init);
    } catch (e) {
      return new Response("backend unreachable: " + e, { status: 502 });
    }

    const outHeaders = new Headers();
    for (const [k, v] of resp.headers) {
      if (!HOP_BY_HOP.has(k.toLowerCase())) outHeaders.set(k, v);
    }
    return new Response(resp.body, {
      status: resp.status,
      statusText: resp.statusText,
      headers: outHeaders,
    });
  },
};
