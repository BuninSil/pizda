package com.phonemcp.voice

/** Shared state of the current Claude voice session, used by the watchdog. */
object VoiceSession {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    @Volatile var active: Boolean = false
    @Volatile var startedAt: Long = 0L
    @Volatile var lastToolCallAt: Long = 0L
    @Volatile var alerted: Boolean = false

    /** The package that was in the foreground before we called Claude, to return to. */
    @Volatile var previousPackage: String? = null

    fun begin(previous: String?) {
        active = true
        startedAt = System.currentTimeMillis()
        lastToolCallAt = System.currentTimeMillis()
        alerted = false
        previousPackage = previous
    }

    fun end() {
        active = false
        alerted = false
    }

    fun onToolCall() {
        lastToolCallAt = System.currentTimeMillis()
        // A fresh command means Claude is alive again; re-arm the alert.
        alerted = false
    }

    /** ms since the last sign of life (tool call or session start). */
    fun idleMs(): Long = System.currentTimeMillis() - maxOf(lastToolCallAt, startedAt)
}
