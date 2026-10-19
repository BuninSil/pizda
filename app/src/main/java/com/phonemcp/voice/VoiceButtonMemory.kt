package com.phonemcp.voice

import android.content.Context

/**
 * Remembers how to find Claude's voice-mode button. Populated either from the
 * built-in candidate list or by "learning mode" — the user taps the button once
 * and we record its signature (id/text/desc) and screen coordinates.
 */
object VoiceButtonMemory {
    private const val PREFS = "phone_mcp"
    private const val KEY_ID = "voice_btn_id"
    private const val KEY_TEXT = "voice_btn_text"
    private const val KEY_DESC = "voice_btn_desc"
    private const val KEY_CX = "voice_btn_cx"
    private const val KEY_CY = "voice_btn_cy"
    private const val KEY_LEARNING = "voice_btn_learning"

    data class Signature(
        val viewId: String?,
        val text: String?,
        val desc: String?,
        val cx: Int,
        val cy: Int
    )

    // Default content-descriptions / texts to try when nothing is learned yet (RU + EN).
    val CANDIDATE_LABELS = listOf(
        "Voice", "Voice mode", "Start voice", "Voice chat", "Talk", "Call",
        "Голос", "Голосовой", "Голосовой режим", "Говорить", "Позвонить",
        "Microphone", "Mic", "Микрофон"
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLearning(c: Context): Boolean = prefs(c).getBoolean(KEY_LEARNING, false)
    fun setLearning(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_LEARNING, v).apply()

    fun get(c: Context): Signature? {
        val p = prefs(c)
        val cx = p.getInt(KEY_CX, -1)
        val cy = p.getInt(KEY_CY, -1)
        val id = p.getString(KEY_ID, null)
        val text = p.getString(KEY_TEXT, null)
        val desc = p.getString(KEY_DESC, null)
        if (cx < 0 && id == null && text == null && desc == null) return null
        return Signature(id, text, desc, cx, cy)
    }

    fun save(c: Context, sig: Signature) {
        prefs(c).edit()
            .putString(KEY_ID, sig.viewId)
            .putString(KEY_TEXT, sig.text)
            .putString(KEY_DESC, sig.desc)
            .putInt(KEY_CX, sig.cx)
            .putInt(KEY_CY, sig.cy)
            .putBoolean(KEY_LEARNING, false)
            .apply()
    }

    fun clear(c: Context) {
        prefs(c).edit()
            .remove(KEY_ID).remove(KEY_TEXT).remove(KEY_DESC)
            .remove(KEY_CX).remove(KEY_CY).remove(KEY_LEARNING)
            .apply()
    }
}
