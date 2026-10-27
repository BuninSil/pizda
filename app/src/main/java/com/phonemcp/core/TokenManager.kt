package com.phonemcp.core

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Generates and stores the secret token that guards the MCP endpoint.
 * The connector URL is https://<tunnel>/<token>/mcp, so a request to the wrong
 * path (wrong token) never even reaches the MCP handler.
 */
object TokenManager {
    private const val PREFS = "phone_mcp"
    private const val KEY_TOKEN = "token"

    /**
     * The token is generated exactly once and then always read back from
     * SharedPreferences. @Synchronized guards the first-run race (several threads
     * — the server and the service — call this at startup); commit() (not apply)
     * writes it to disk synchronously so an aggressive OEM kill right after first
     * launch can't lose it and cause a new token on the next start.
     */
    @Synchronized
    fun getToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val token = generate()
        prefs.edit().putString(KEY_TOKEN, token).commit()
        return token
    }

    @Synchronized
    fun regenerate(context: Context): String {
        val token = generate()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).commit()
        return token
    }

    private fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Constant-time comparison so timing can't be used to guess the token. */
    fun matches(expected: String, provided: String?): Boolean {
        if (provided == null) return false
        val a = expected.toByteArray()
        val b = provided.toByteArray()
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
