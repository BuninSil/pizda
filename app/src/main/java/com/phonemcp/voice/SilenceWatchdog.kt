package com.phonemcp.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.phonemcp.accessibility.ControllerAccessibilityService
import com.phonemcp.core.Settings
import com.phonemcp.core.ToolLog

/**
 * While a voice session is active, watches for three "Claude went quiet"
 * conditions and fires an alert once (re-armed when a new tool call arrives):
 *   1) no MCP commands for longer than the configured timeout,
 *   2) a limit/quota message on Claude's screen,
 *   3) the call appears to have dropped (Claude no longer foreground).
 */
class SilenceWatchdog(
    private val context: Context,
    private val onAlert: (reason: String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val intervalMs = 5000L
    @Volatile private var running = false

    private val limitKeywords = listOf(
        "limit", "message limit", "out of messages", "reached your", "upgrade to",
        "run out", "usage limit",
        "лимит", "достигнут лимит", "закончились сообщения", "перейдите на", "превышен"
    )

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { check() } catch (_: Exception) {}
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(tick, intervalMs)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun check() {
        if (!VoiceSession.active || VoiceSession.alerted) return
        val svc = ControllerAccessibilityService.instance ?: return

        // Give Claude a grace period right after start before complaining.
        if (System.currentTimeMillis() - VoiceSession.startedAt < 8000) return

        // 2) limit message on Claude screen
        if (svc.activePackage() == VoiceSession.CLAUDE_PACKAGE &&
            svc.screenContainsAny(limitKeywords)
        ) {
            fire("на экране Claude сообщение про лимит")
            return
        }

        // 3) call dropped: only meaningful when we did NOT navigate away ourselves
        if (!Settings.returnAfterStart(context) &&
            svc.activePackage() != VoiceSession.CLAUDE_PACKAGE &&
            svc.activePackage() != context.packageName
        ) {
            fire("звонок в Claude, похоже, оборвался")
            return
        }

        // 1) silence
        val timeoutMs = Settings.silenceTimeoutSec(context) * 1000L
        if (VoiceSession.idleMs() > timeoutMs) {
            fire("нет команд дольше ${Settings.silenceTimeoutSec(context)} сек")
            return
        }
    }

    private fun fire(reason: String) {
        VoiceSession.alerted = true
        ToolLog.add("watchdog", "alert: $reason", false)
        onAlert(reason)
    }
}
