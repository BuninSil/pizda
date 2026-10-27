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
import com.phonemcp.BuildConfig
import com.phonemcp.R
import com.phonemcp.core.AppState
import com.phonemcp.core.Settings
import com.phonemcp.core.TokenManager
import com.phonemcp.core.ToolLog
import com.phonemcp.relay.RelayClient
import com.phonemcp.ui.MainActivity
import com.phonemcp.update.UpdateManager
import com.phonemcp.voice.ClaudeLauncher
import com.phonemcp.voice.SilenceWatchdog
import com.phonemcp.voice.VoiceSession
import java.util.Locale

/**
 * Keeps the relay WebSocket to the Worker alive with a persistent notification;
 * also orchestrates the Claude voice call, the silence watchdog (TTS alert), and
 * the floating button.
 *
 * There is no local HTTP server and no tunnel any more: the phone dials out to the
 * Worker over WSS/443, the Worker's Durable Object pushes Claude's MCP requests
 * down that socket, and we answer them in-process via [RelayClient] + McpHandler.
 */
class McpForegroundService : Service() {

    private var relay: RelayClient? = null
    private var started = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var watchdog: SilenceWatchdog? = null
    private var overlay: OverlayManager? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

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
            acquireWakeLock()
            registerNetworkCallback()
            startRelay()
            // First update check ~30s after start, then every few hours.
            updateHandler.postDelayed(updateTick, 30_000)
        }

        if (Settings.overlayEnabled(applicationContext)) showOverlay()

        when (intent?.action) {
            ACTION_CALL, ACTION_RESTART -> callClaudeAsync()
            ACTION_SHOW_OVERLAY -> { Settings.setOverlayEnabled(applicationContext, true); showOverlay() }
            ACTION_HIDE_OVERLAY -> { Settings.setOverlayEnabled(applicationContext, false); overlay?.hide() }
            ACTION_UPDATE -> doUpdateAsync()
            ACTION_APPLY_WORKER -> startRelay() // Worker URL changed — reconnect.
        }
        return START_STICKY
    }

    // --- Relay ------------------------------------------------------------

    /** The Worker base: the app override if set, else the value baked in at build. */
    private fun workerBase(): String {
        val s = Settings.workerBase(applicationContext)
        return (if (s.isNotBlank()) s else BuildConfig.RELAY_WORKER).trim().trimEnd('/')
    }

    private fun startRelay() {
        // Drop any previous connection first.
        relay?.stop()
        relay = null

        val base = workerBase()
        if (base.isBlank()) {
            AppState.setStatus("Не задан адрес Worker. Открой «Постоянный адрес» и впиши URL.")
            ToolLog.add("relay", "worker url пустой", false)
            return
        }
        val token = TokenManager.getToken(applicationContext)
        // The connector URL is stable the moment we know the Worker + token — it
        // does not depend on the socket being up (Claude just gets 503 while the
        // phone is offline). Set it once and keep it.
        val full = "$base/$token/mcp"
        AppState.setConnectorUrl(full)
        copyToClipboard(full)
        AppState.setStatus("Подключаюсь к релею…")
        updateNotification("Подключаюсь к релею…", full)

        val r = RelayClient(
            context = applicationContext,
            workerBase = base,
            secret = BuildConfig.RELAY_SECRET,
            onState = { connected -> onRelayState(connected, full) },
        )
        relay = r
        r.start()
    }

    private fun onRelayState(connected: Boolean, connectorFull: String) {
        AppState.relayConnected = connected
        if (connected) {
            AppState.setStatus("На связи. Постоянный адрес готов.")
            updateNotification("Коннектор готов (постоянный)", connectorFull)
        } else {
            AppState.setStatus("Связь с релеем потеряна — переподключаюсь…")
            updateNotification("Переподключаюсь к релею…", connectorFull)
        }
    }

    // --- In-app update ----------------------------------------------------

    private fun checkForUpdateAsync() {
        Thread {
            val info = (UpdateManager.check() as? UpdateManager.Outcome.Available)?.info ?: return@Thread
            ToolLog.add("update", "available: ${info.tag}", true)
            notifyUpdate(info.tag)
        }.start()
    }

    private fun doUpdateAsync() {
        Thread {
            val info = when (val o = UpdateManager.check()) {
                is UpdateManager.Outcome.Available -> o.info
                is UpdateManager.Outcome.UpToDate -> { notifySimple("Обновлений нет", "У тебя уже последняя версия."); return@Thread }
                is UpdateManager.Outcome.Failed -> { notifySimple("Не удалось проверить", o.reason); return@Thread }
            }
            notifySimple("Скачиваю обновление…", info.tag)
            val apk = when (val dl = UpdateManager.download(applicationContext, info)) {
                is UpdateManager.DownloadResult.Ok -> dl.file
                is UpdateManager.DownloadResult.Err -> {
                    notifySimple("Не удалось скачать обновление", "${dl.reason}. Можно поставить вручную по ссылке из релизов.")
                    return@Thread
                }
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

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "phonemcp:relay").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    // Network (re)appeared — nudge the relay to reconnect at once.
                    if (started) relay?.reconnect()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {}
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
            ).apply { description = "MCP relay status" }
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
        try { netCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        netCallback = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        VoiceSession.end()
        relay?.stop()
        relay = null
        tts?.shutdown()
        AppState.relayConnected = false
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
        const val ACTION_APPLY_WORKER = "com.phonemcp.APPLY_WORKER"
    }
}
