package com.phonemcp.relay

import android.content.Context
import android.util.Base64
import com.phonemcp.core.TokenManager
import com.phonemcp.core.ToolLog
import com.phonemcp.mcp.McpHandler
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Holds an outbound WebSocket to the relay Worker (wss://<worker>/<token>/__phone,
 * plain 443). The Worker's Durable Object forwards each of Claude's MCP requests
 * over this socket as JSON; we run them through [McpHandler] and send the response
 * back. No inbound ports, no tunnel — so a provider or VPN that blocks
 * cloudflared's :7844 can't touch this.
 *
 * Reconnects with exponential backoff (capped at 30s); call [reconnect] on a
 * network change to retry immediately.
 */
class RelayClient(
    private val context: Context,
    private val workerBase: String,
    private val secret: String,
    private val onState: (connected: Boolean) -> Unit,
) {
    private val handler = McpHandler(context)
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS) // protocol-level keepalive
        .readTimeout(0, TimeUnit.MILLISECONDS) // never time out the long-lived socket
        .build()

    // One thread drives (re)connects/pings; a small pool runs MCP calls so a slow
    // tool (accessibility, screenshot) never blocks the socket reader or pings.
    private val sched: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val workers = Executors.newFixedThreadPool(3)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var connected = false
    private var attempt = 0
    private var pingTask: java.util.concurrent.ScheduledFuture<*>? = null

    private fun wssUrl(): String {
        val token = TokenManager.getToken(context)
        val base = workerBase.trim().trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$base/$token/__phone"
    }

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        pingTask?.cancel(false)
        try { ws?.close(1000, "stop") } catch (_: Exception) {}
        ws = null
        setConnected(false)
        try { sched.shutdownNow() } catch (_: Exception) {}
        try { workers.shutdownNow() } catch (_: Exception) {}
    }

    /** Force an immediate reconnect (e.g. the default network changed). */
    fun reconnect() {
        if (stopped) return
        sched.execute {
            try { ws?.close(1001, "reconnect") } catch (_: Exception) {}
            ws = null
            attempt = 0
            connect()
        }
    }

    private fun connect() {
        if (stopped) return
        val url = wssUrl()
        val reqBuilder = Request.Builder().url(url)
        if (secret.isNotBlank()) reqBuilder.addHeader("x-update-secret", secret)
        ToolLog.add("relay", "подключаюсь: $url", true)
        ws = client.newWebSocket(reqBuilder.build(), Listener())
    }

    private fun scheduleReconnect() {
        if (stopped) return
        setConnected(false)
        val delay = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L) // 1s..30s
        attempt++
        ToolLog.add("relay", "переподключение через ${delay / 1000}с", false)
        sched.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun startPing(sock: WebSocket) {
        pingTask?.cancel(false)
        pingTask = sched.scheduleWithFixedDelay({
            try { sock.send(JSONObject().put("type", "ping").toString()) } catch (_: Exception) {}
        }, 20, 20, TimeUnit.SECONDS)
    }

    private fun setConnected(value: Boolean) {
        if (connected != value) {
            connected = value
            onState(value)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            setConnected(true)
            startPing(webSocket)
            ToolLog.add("relay", "на связи с воркером", true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try { JSONObject(text) } catch (e: Exception) { return }
            if (msg.optString("type") == "pong") return
            val id = msg.optString("id").ifBlank { return }
            // Run the MCP call off the socket thread so pings/other requests flow.
            workers.execute { serveRequest(webSocket, id, msg) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ToolLog.add("relay", "обрыв: ${t.message ?: t.javaClass.simpleName}", false)
            pingTask?.cancel(false)
            if (webSocket === ws) scheduleReconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            ToolLog.add("relay", "закрытие: $code $reason", code == 1000)
            pingTask?.cancel(false)
            try { webSocket.close(1000, null) } catch (_: Exception) {}
            if (webSocket === ws && !stopped) scheduleReconnect()
        }
    }

    private fun serveRequest(sock: WebSocket, id: String, msg: JSONObject) {
        val method = msg.optString("method", "POST")
        val path = msg.optString("path", "")
        val bodyB64 = msg.optString("bodyB64", "")
        val body = if (bodyB64.isBlank()) ByteArray(0)
            else try { Base64.decode(bodyB64, Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }

        val resp = try {
            handler.handle(method, path, body)
        } catch (e: Exception) {
            McpHandler.Resp(500, "text/plain", ("handler error: " + e.message).toByteArray())
        }

        val headers = JSONObject()
        headers.put("content-type", resp.contentType)
        for ((k, v) in resp.headers) headers.put(k, v)

        val out = JSONObject()
            .put("id", id)
            .put("status", resp.status)
            .put("headers", headers)
            .put("bodyB64", Base64.encodeToString(resp.body, Base64.NO_WRAP))
        try { sock.send(out.toString()) } catch (_: Exception) {}

        // Log with status-based mark, as requested: 2xx/3xx ok, 4xx/5xx not.
        val shortPath = path.substringAfterLast('/')
        ToolLog.add("relay", "$method /$shortPath → ${resp.status}", resp.status < 400)
    }
}
