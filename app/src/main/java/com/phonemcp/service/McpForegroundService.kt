package com.phonemcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.phonemcp.R
import com.phonemcp.core.AppState
import com.phonemcp.core.Settings
import com.phonemcp.core.TokenManager
import com.phonemcp.core.ToolLog
import com.phonemcp.mcp.McpServer
import com.phonemcp.tunnel.CloudflaredManager
import com.phonemcp.ui.MainActivity
import com.phonemcp.update.UpdateManager
import com.phonemcp.voice.ClaudeLauncher
import com.phonemcp.voice.SilenceWatchdog
import com.phonemcp.voice.VoiceSession
import java.util.Locale

/**
 * Keeps the MCP server + cloudflared tunnel alive with a persistent
 * notification; also orchestrates the Claude voice call, the silence watchdog
 * (TTS alert), and the floating button.
 */
class McpForegroundService : Service() {

    private var server: McpServer? = null
    private var cloudflared: CloudflaredManager? = null
    private var started = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var watchdog: SilenceWatchdog? = null
    private var overlay: OverlayManager? = null

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateIntervalMs = 3L * 60 * 60 * 1000 // every 3 hours
    private val updateTick = object : Runnable {
        override fun run() {
            checkForUpdateAsync()
            updateHandler.postDelayed(this, updateIntervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru")
                ttsReady = true
            }
        }
        watchdog = SilenceWatchdog(applicationContext) { reason -> onSilenceAlert(reason) }
        overlay = OverlayManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Config.NOTIF_ID, buildNotification("Запуск…", null))
        AppState.setRunning(true)

        if (!started) {
            started = true
            AppState.setStatus("Запуск сервера…")
            startServer()
            tunnelAttempt = 0
            startTunnel()
            // First update check ~30s after start, then every few hours.
            updateHandler.postDelayed(updateTick, 30_000)
        }

        if (Settings.overlayEnabled(applicationContext)) showOverlay()

        when (intent?.action) {
            ACTION_CALL, ACTION_RESTART -> callClaudeAsync()
            ACTION_SHOW_OVERLAY -> { Settings.setOverlayEnabled(applicationContext, true); showOverlay() }
            ACTION_HIDE_OVERLAY -> { Settings.setOverlayEnabled(applicationContext, false); overlay?.hide() }
            ACTION_UPDATE -> doUpdateAsync()
        }
        return START_STICKY
    }

    // --- In-app update ----------------------------------------------------

    private fun checkForUpdateAsync() {
        Thread {
            val info = UpdateManager.check() ?: return@Thread
            ToolLog.add("update", "available: ${info.tag}", true)
            notifyUpdate(info.tag)
        }.start()
    }

    private fun doUpdateAsync() {
        Thread {
            val info = UpdateManager.check()
            if (info == null) {
                notifySimple("Обновлений нет", "У тебя уже последняя версия.")
                return@Thread
            }
            notifySimple("Скачиваю обновление…", info.tag)
            val apk = UpdateManager.download(applicationContext, info)
            if (apk == null) {
                notifySimple("Не удалось скачать", "Проверь сеть и доступность релиза.")
                return@Thread
            }
            try {
                UpdateManager.install(applicationContext, apk)
            } catch (e: Exception) {
                notifySimple("Не удалось открыть установщик", "Разреши установку из этого источника. ${e.message}")
            }
        }.start()
    }

    private fun notifyUpdate(tag: String) {
        val n = Notification.Builder(this, Config.ALERT_CHANNEL)
            .setContentTitle("Есть обновление ($tag)")
            .setContentText("Нажми «Обновить», чтобы скачать и установить.")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Обновить", pendingService(ACTION_UPDATE, 5)).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(Config.UPDATE_ID, n)
    }

    private fun startServer() {
        try {
            val s = McpServer(applicationContext, Config.LOCAL_PORT)
            s.start(0, false)
            server = s
            AppState.setStatus("Сервер на 127.0.0.1:${Config.LOCAL_PORT}. Поднимаю туннель…")
            ToolLog.add("server", "started on :${Config.LOCAL_PORT}", true)
        } catch (e: Exception) {
            AppState.setStatus("Ошибка сервера: ${e.message}")
            ToolLog.add("server", "error: ${e.message}", false)
        }
    }

    private var tunnelAttempt = 0

    private fun startTunnel() {
        val cf = CloudflaredManager(applicationContext)
        cloudflared = cf
        if (!cf.isEmbedded()) {
            AppState.setStatus(
                "cloudflared не встроен. Используй Termux (см. README) или запусти туннель на порт ${Config.LOCAL_PORT}."
            )
            ToolLog.add("tunnel", "binary not embedded", false)
            return
        }
        tunnelAttempt++
        cf.start(
            localPort = Config.LOCAL_PORT,
            onUrl = { base -> tunnelAttempt = 0; onTunnelUp(base) },
            onLog = { line ->
                val bad = line.contains("err", true) || line.contains("fail", true) || line.contains("fatal", true)
                ToolLog.add("cloudflared", line, !bad)
            },
            onExit = { code ->
                AppState.setConnectorUrl(null)
                ToolLog.add("tunnel", "exited: $code", false)
                // Auto-retry a few times unless we were deliberately stopped.
                if (!cf.stopped && started && tunnelAttempt < MAX_TUNNEL_ATTEMPTS) {
                    val delay = 3000L * tunnelAttempt
                    AppState.setStatus("Туннель упал (код $code). Перезапуск через ${delay / 1000}с… (попытка ${tunnelAttempt + 1}/$MAX_TUNNEL_ATTEMPTS)")
                    updateNotification("Перезапускаю туннель…", null)
                    updateHandler.postDelayed({ if (started) startTunnel() }, delay)
                } else if (!cf.stopped) {
                    AppState.setStatus("Туннель не поднялся (код $code). Смотри лог ниже; попробуй ещё раз или Termux.")
                    updateNotification("Туннель не поднялся", null)
                }
            }
        )
    }

    private fun onTunnelUp(baseUrl: String) {
        val token = TokenManager.getToken(applicationContext)
        val full = "$baseUrl/$token/mcp"
        AppState.setConnectorUrl(full)
        AppState.setStatus("Готово. Коннектор доступен.")
        ToolLog.add("tunnel", "up: $baseUrl", true)
        copyToClipboard(full)
        updateNotification("Коннектор готов", full)
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MCP connector URL", text))
        } catch (e: Exception) {
            // Some OEMs block background clipboard writes; the buttons still work.
        }
    }

    // --- Voice call -------------------------------------------------------

    private fun callClaudeAsync() {
        Thread {
            val result = ClaudeLauncher.callClaude(applicationContext)
            when (result) {
                ClaudeLauncher.Result.STARTED -> {
                    if (Settings.watchdogEnabled(applicationContext)) watchdog?.start()
                    updateNotification("Голосовой режим Claude запущен", AppState.connectorUrl)
                }
                ClaudeLauncher.Result.NEED_LEARNING ->
                    notifySimple("Не нашёл кнопку голоса", "Режим обучения: нажми кнопку голосового режима в Claude сам — приложение её запомнит.")
                ClaudeLauncher.Result.CLAUDE_NOT_INSTALLED ->
                    notifySimple("Claude не установлен", "Установи приложение Claude (com.anthropic.claude).")
                ClaudeLauncher.Result.NO_ACCESSIBILITY ->
                    notifySimple("Нет доступа", "Включи службу специальных возможностей Phone MCP.")
            }
        }.start()
    }

    private fun onSilenceAlert(reason: String) {
        val phrase = "Claude замолчал, возможно, закончился лимит или оборвался звонок"
        if (ttsReady) {
            tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "silence_alert")
        }
        notifyAlert(reason)
    }

    private fun showOverlay() {
        val ov = overlay ?: return
        if (!ov.canDraw()) {
            ToolLog.add("overlay", "no draw-over permission", false)
            return
        }
        ov.show {
            val i = Intent(this, McpForegroundService::class.java).setAction(ACTION_CALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }
    }

    // --- Notifications ----------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val main = NotificationChannel(
                Config.NOTIF_CHANNEL, "Phone MCP", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "MCP server & tunnel status" }
            val alert = NotificationChannel(
                Config.ALERT_CHANNEL, "Phone MCP alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Claude silence / limit alerts" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(main)
            nm.createNotificationChannel(alert)
        }
    }

    private fun pendingService(action: String, req: Int): PendingIntent {
        val i = Intent(this, McpForegroundService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(this, req, i, flags)
        else PendingIntent.getService(this, req, i, flags)
    }

    private fun pendingBroadcast(action: String, req: Int): PendingIntent {
        val i = Intent(this, ServiceActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            this, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(status: String, url: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = url ?: status
        val builder = Notification.Builder(this, Config.NOTIF_CHANNEL)
            .setContentTitle("Phone MCP — $status")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Стоп", pendingBroadcast(ServiceActionReceiver.ACTION_STOP, 1)).build())
            .addAction(Notification.Action.Builder(null, "Позвать Claude", pendingService(ACTION_CALL, 3)).build())

        if (url != null) {
            builder.addAction(Notification.Action.Builder(null, "Копировать", pendingBroadcast(ServiceActionReceiver.ACTION_COPY, 2)).build())
        }
        return builder.build()
    }

    private fun updateNotification(status: String, url: String?) {
        getSystemService(NotificationManager::class.java).notify(Config.NOTIF_ID, buildNotification(status, url))
    }

    private fun notifyAlert(reason: String) {
        val n = Notification.Builder(this, Config.ALERT_CHANNEL)
            .setContentTitle("Claude замолчал")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText("$reason.\nВозможно, закончился лимит или оборвался звонок."))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Перезапустить звонок", pendingService(ACTION_RESTART, 4)).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(Config.ALERT_ID, n)
    }

    private fun notifySimple(title: String, body: String) {
        val n = Notification.Builder(this, Config.ALERT_CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(Config.ALERT_ID, n)
    }

    override fun onDestroy() {
        watchdog?.stop()
        overlay?.hide()
        updateHandler.removeCallbacks(updateTick)
        VoiceSession.end()
        server?.stop()
        cloudflared?.stop()
        tts?.shutdown()
        server = null
        cloudflared = null
        AppState.setConnectorUrl(null)
        AppState.setRunning(false)
        AppState.setStatus("Остановлено")
        ToolLog.add("service", "stopped", true)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CALL = "com.phonemcp.CALL_CLAUDE"
        const val ACTION_RESTART = "com.phonemcp.RESTART_CALL"
        const val ACTION_SHOW_OVERLAY = "com.phonemcp.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.phonemcp.HIDE_OVERLAY"
        const val ACTION_UPDATE = "com.phonemcp.UPDATE"
        private const val MAX_TUNNEL_ATTEMPTS = 5
    }
}
