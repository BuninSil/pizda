package com.phonemcp.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.phonemcp.accessibility.ControllerAccessibilityService
import com.phonemcp.core.Settings
import com.phonemcp.core.ToolLog

/**
 * Opens the Claude app and starts voice mode: deep link first (if any works),
 * otherwise launch the app and tap the voice button via accessibility. If the
 * button can't be found, enables learning mode so the user taps it once.
 */
object ClaudeLauncher {

    enum class Result { STARTED, NEED_LEARNING, CLAUDE_NOT_INSTALLED, NO_ACCESSIBILITY }

    /** Blocks (call from a worker thread). */
    fun callClaude(context: Context): Result {
        val svc = ControllerAccessibilityService.instance ?: return Result.NO_ACCESSIBILITY

        // Remember where we were, so we can return later.
        val prev = svc.activePackage()
            ?.takeIf { it != VoiceSession.CLAUDE_PACKAGE && it != context.packageName }

        if (!isInstalled(context, VoiceSession.CLAUDE_PACKAGE)) {
            return Result.CLAUDE_NOT_INSTALLED
        }

        // 1) Try deep links.
        var opened = false
        for (link in Settings.deepLinks(context)) {
            if (tryDeepLink(context, link)) {
                opened = true
                ToolLog.add("voice", "deep link ok: $link", true)
                break
            }
        }

        // 2) Fallback: launch the app normally.
        if (!opened) {
            val launch = context.packageManager.getLaunchIntentForPackage(VoiceSession.CLAUDE_PACKAGE)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            } else {
                return Result.CLAUDE_NOT_INSTALLED
            }
        }

        // Wait for Claude to come to the foreground.
        waitForPackage(svc, VoiceSession.CLAUDE_PACKAGE, 6000)
        // Give the UI a moment to settle.
        sleep(1200)

        // If a deep link already dropped us into voice mode we may not need a tap,
        // but tapping the voice button is still the reliable path.
        val tapped = svc.findAndTapVoiceButton()
        if (!tapped) {
            VoiceButtonMemory.setLearning(context, true)
            ToolLog.add("voice", "voice button not found — learning mode on", false)
            return Result.NEED_LEARNING
        }

        VoiceSession.begin(prev)
        ToolLog.add("voice", "voice mode started", true)

        // Optionally return to the previous app / home.
        if (Settings.returnAfterStart(context)) {
            sleep(2500)
            if (prev != null) {
                context.packageManager.getLaunchIntentForPackage(prev)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(it) } catch (_: Exception) { svc.home() }
                } ?: svc.home()
            } else {
                svc.home()
            }
        }
        return Result.STARTED
    }

    private fun tryDeepLink(context: Context, uri: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                setPackage(VoiceSession.CLAUDE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) return false
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getLaunchIntentForPackage(pkg) != null ||
            context.packageManager.getPackageInfo(pkg, 0) != null
    } catch (e: Exception) {
        false
    }

    private fun waitForPackage(svc: ControllerAccessibilityService, pkg: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (svc.activePackage() == pkg) return
            sleep(200)
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
}
