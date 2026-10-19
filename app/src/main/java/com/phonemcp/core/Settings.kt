package com.phonemcp.core

import android.content.Context

/** User-tunable settings. */
object Settings {
    private const val PREFS = "phone_mcp"
    private const val KEY_SILENCE = "silence_timeout_sec"
    private const val KEY_RETURN = "return_after_start"
    private const val KEY_DEEPLINKS = "voice_deeplinks"
    private const val KEY_CALL_ON_LAUNCH = "call_on_launch"
    private const val KEY_OVERLAY = "overlay_enabled"

    // Candidate deep links to try before falling back to an accessibility tap.
    // No official Claude voice deep link is documented, so these are best-effort
    // and editable by the user.
    val DEFAULT_DEEPLINKS = listOf(
        "claude://voice",
        "anthropic://voice",
        "claude://chat?mode=voice"
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun silenceTimeoutSec(c: Context): Int = prefs(c).getInt(KEY_SILENCE, 60)
    fun setSilenceTimeoutSec(c: Context, v: Int) = prefs(c).edit().putInt(KEY_SILENCE, v).apply()

    fun returnAfterStart(c: Context): Boolean = prefs(c).getBoolean(KEY_RETURN, false)
    fun setReturnAfterStart(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_RETURN, v).apply()

    fun callClaudeOnLaunch(c: Context): Boolean = prefs(c).getBoolean(KEY_CALL_ON_LAUNCH, false)
    fun setCallClaudeOnLaunch(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_CALL_ON_LAUNCH, v).apply()

    fun overlayEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_OVERLAY, false)
    fun setOverlayEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_OVERLAY, v).apply()

    fun deepLinks(c: Context): List<String> {
        val raw = prefs(c).getString(KEY_DEEPLINKS, null) ?: return DEFAULT_DEEPLINKS
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    fun setDeepLinks(c: Context, links: List<String>) =
        prefs(c).edit().putString(KEY_DEEPLINKS, links.joinToString("\n")).apply()
}
