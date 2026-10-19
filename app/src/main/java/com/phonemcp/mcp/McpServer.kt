package com.phonemcp.mcp

import android.content.Context
import com.phonemcp.core.TokenManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Minimal MCP server over the Streamable-HTTP transport, bound to localhost.
 * The cloudflared tunnel forwards the public URL to this port.
 *
 * The secret token is part of the path: /<token>/mcp . A request to any other
 * path is rejected before reaching the JSON-RPC handler.
 */
class McpServer(
    private val context: Context,
    port: Int
) : NanoHTTPD("127.0.0.1", port) {

    private val tools = McpTools(context)
    private val serverName = "phone-mcp"
    private val serverVersion = "0.1"
    private val protocolVersion = "2024-11-05"

    override fun serve(session: IHTTPSession): Response {
        val token = TokenManager.getToken(context)
        val path = session.uri.trim('/')
        val expectedPath = "$token/mcp"

        // Constant-ish path/token check.
        if (!pathMatches(expectedPath, path)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }

        return when (session.method) {
            Method.POST -> handlePost(session)
            Method.GET -> newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED, "text/plain", "use POST"
            )
            else -> newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED, "text/plain", "use POST"
            )
        }
    }

    private fun pathMatches(expected: String, actual: String): Boolean {
        // expected = "<token>/mcp" ; compare the token part constant-time.
        val eTok = expected.substringBeforeLast("/mcp", "")
        val aTok = actual.substringBeforeLast("/mcp", "")
        if (!actual.endsWith("/mcp")) return false
        return TokenManager.matches(eTok, aTok)
    }

    private fun handlePost(session: IHTTPSession): Response {
        val body = try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            map["postData"] ?: ""
        } catch (e: Exception) {
            return jsonError(null, -32700, "parse error")
        }

        val parsed = try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (e: Exception) {
            return jsonError(null, -32700, "invalid JSON")
        }

        // Batch of requests.
        if (parsed is JSONArray) {
            val responses = JSONArray()
            for (i in 0 until parsed.length()) {
                val resp = handleRpc(parsed.getJSONObject(i))
                if (resp != null) responses.put(resp)
            }
            return if (responses.length() == 0)
                newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "")
            else ok(responses.toString())
        }

        val req = parsed as JSONObject
        val resp = handleRpc(req)
            ?: return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "")
        return ok(resp.toString())
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

    private fun ok(bodyJson: String): Response {
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", bodyJson)
        resp.addHeader("Mcp-Session-Id", sessionId)
        return resp
    }

    private fun jsonError(id: Any?, code: Int, message: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", rpcError(id, code, message).toString())

    private val sessionId: String by lazy {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        b.joinToString("") { "%02x".format(it) }
    }
}
