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

    fun getToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var token = prefs.getString(KEY_TOKEN, null)
        if (token.isNullOrBlank()) {
            token = generate()
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }
        return token
    }

    fun regenerate(context: Context): String {
        val token = generate()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
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
