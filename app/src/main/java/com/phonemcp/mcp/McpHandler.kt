package com.phonemcp.mcp

import android.content.Context
import com.phonemcp.core.TokenManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * The MCP Streamable-HTTP logic, with no HTTP server attached. The relay delivers
 * each of Claude's requests (method + full path + body) straight into [handle],
 * which runs the JSON-RPC and returns the bytes to send back — so there is no
 * localhost socket and nothing for a provider or VPN to block.
 *
 * The secret token is the first path segment: /<token>/mcp . A request to any
 * other path (wrong token) is rejected with 404 — never 401, because claude.ai
 * reads 401 as "this resource wants OAuth" and aborts with "Не удалось начать вход".
 */
class McpHandler(private val context: Context) {

    private val tools = McpTools(context)
    private val serverName = "phone-mcp"
    private val serverVersion = "0.1"
    private val protocolVersion = "2024-11-05"

    /** A ready-to-send HTTP-ish response. [body] is the raw bytes. */
    data class Resp(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    )

    /**
     * @param method  HTTP method ("POST", "GET", "DELETE", …).
     * @param fullPath path including the token, e.g. "/<token>/mcp".
     * @param body     raw request body bytes (empty for GET).
     */
    fun handle(method: String, fullPath: String, body: ByteArray): Resp {
        val token = TokenManager.getToken(context)
        val path = fullPath.substringBefore('?').trim('/')
        val slash = path.indexOf('/')
        val providedToken = if (slash >= 0) path.substring(0, slash) else path
        if (!TokenManager.matches(token, providedToken)) {
            return text(
                404,
                "unknown path: wrong or missing token in the connector URL. " +
                    "This is not an OAuth-protected resource — check the full " +
                    "https://<worker>/<token>/mcp URL from the app."
            )
        }
        val rest = path.removePrefix("$token/")
        return when {
            rest == "mcp" && method == "POST" -> handlePost(body)
            // DELETE ends the Streamable-HTTP session; we are stateless, so just ack.
            rest == "mcp" && method == "DELETE" -> text(200, "bye")
            // GET would open a server->client SSE stream; we don't push, so 405.
            rest == "mcp" -> text(405, "use POST")
            rest.startsWith("file/") -> serveFile(rest.removePrefix("file/"))
            else -> text(404, "not found")
        }
    }

    private fun serveFile(name: String): Resp {
        val f = FileStore.get(context, name) ?: return text(404, "not found")
        return try {
            Resp(200, FileStore.contentType(name), f.readBytes())
        } catch (e: Exception) {
            text(500, "read error")
        }
    }

    private fun handlePost(body: ByteArray): Resp {
        val raw = String(body, Charsets.UTF_8).trim()
        val parsed = try {
            if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
        } catch (e: Exception) {
            return json(rpcError(null, -32700, "invalid JSON").toString())
        }

        if (parsed is JSONArray) {
            val responses = JSONArray()
            for (i in 0 until parsed.length()) {
                val resp = handleRpc(parsed.getJSONObject(i))
                if (resp != null) responses.put(resp)
            }
            return if (responses.length() == 0) accepted() else json(responses.toString())
        }

        val resp = handleRpc(parsed as JSONObject) ?: return accepted()
        return json(resp.toString())
    }

    /** @return the JSON-RPC response object, or null for notifications (no id). */
    private fun handleRpc(req: JSONObject): JSONObject? {
        val method = req.optString("method")
        val hasId = req.has("id") && !req.isNull("id")
        val id = if (hasId) req.get("id") else null

        when (method) {
            "initialize" -> {
                val clientVersion = req.optJSONObject("params")?.optString("protocolVersion")
                val negotiated = if (clientVersion.isNullOrBlank()) protocolVersion else clientVersion
                val result = JSONObject()
                    .put("protocolVersion", negotiated)
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", serverName).put("version", serverVersion))
                return result(id, result)
            }
            "notifications/initialized", "notifications/cancelled" -> return null
            "ping" -> return result(id, JSONObject())
            "tools/list" -> return result(id, JSONObject().put("tools", tools.toolsList()))
            "tools/call" -> {
                val params = req.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name")
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                val toolResult = tools.call(name, arguments)
                return result(id, toolResult)
            }
            else -> {
                if (!hasId) return null
                return rpcError(id, -32601, "method not found: $method")
            }
        }
    }

    private fun result(id: Any?, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun json(bodyJson: String): Resp =
        Resp(200, JSON_MIME, bodyJson.toByteArray(Charsets.UTF_8), mapOf("Mcp-Session-Id" to sessionId))

    private fun accepted(): Resp = Resp(202, JSON_MIME, ByteArray(0))

    private fun text(status: Int, body: String): Resp =
        Resp(status, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private companion object {
        const val JSON_MIME = "application/json; charset=utf-8"
    }

    private val sessionId: String by lazy {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        b.joinToString("") { "%02x".format(it) }
    }
}
