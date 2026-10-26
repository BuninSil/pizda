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
import com.phonemcp.core.VpnStatus
import com.phonemcp.tunnel.CloudflaredManager
import com.phonemcp.tunnel.DohResolver
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

    // Self-heal: while a Worker is configured, keep re-pushing the current tunnel
    // base to it so its stable URL always points at a live tunnel even after a
    // flap where a push was missed.
    private val workerIntervalMs = 90_000L
    private val workerTick = object : Runnable {
        override fun run() {
            val wb = Settings.workerBase(applicationContext)
            val base = AppState.tunnelBase
            if (wb.isNotBlank() && !base.isNullOrBlank()) pushBackendToWorker(wb, base)
            updateHandler.postDelayed(this, workerIntervalMs)
        }
    }

    // Local server liveness: if NanoHTTPD died, cloudflared gets connection
    // refused on :8765 and the connector returns 502 — so check it and restart.
    private val serverIntervalMs = 15_000L
    private val serverTick = object : Runnable {
        override fun run() {
            if (started) {
                // Probe off the main thread (socket connect can block briefly).
                Thread {
                    if (started && !isServerAlive()) {
                        ToolLog.add("server", "сервер не отвечает на :${Config.LOCAL_PORT} — перезапуск", false)
                        startServer()
                    }
                }.start()
            }
            updateHandler.postDelayed(this, serverIntervalMs)
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
            AppState.setStatus("Запуск сервера…")
            startServer()
            tunnelAttempt = 0
            startTunnel()
            // First update check ~30s after start, then every few hours.
            updateHandler.postDelayed(updateTick, 30_000)
            // Keep the Worker's backend fresh (self-heal after tunnel flaps).
            updateHandler.postDelayed(workerTick, workerIntervalMs)
            // Watch the local server and restart it if it dies (prevents 502).
            updateHandler.postDelayed(serverTick, serverIntervalMs)
        }

        if (Settings.overlayEnabled(applicationContext)) showOverlay()

        when (intent?.action) {
            ACTION_CALL, ACTION_RESTART -> callClaudeAsync()
            ACTION_SHOW_OVERLAY -> { Settings.setOverlayEnabled(applicationContext, true); showOverlay() }
            ACTION_HIDE_OVERLAY -> { Settings.setOverlayEnabled(applicationContext, false); overlay?.hide() }
            ACTION_UPDATE -> doUpdateAsync()
            ACTION_APPLY_WORKER -> AppState.tunnelBase?.let { onTunnelUp(it) }
        }
        return START_STICKY
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

    private fun startServer() {
        // Drop any previous (possibly dead) instance so the port is free.
        try { server?.stop() } catch (_: Exception) {}
        server = null
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

    /** True if NanoHTTPD is alive AND the port actually accepts a TCP connection. */
    private fun isServerAlive(): Boolean {
        val s = server ?: return false
        if (!s.isAlive) return false
        return try {
            java.net.Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress("127.0.0.1", Config.LOCAL_PORT), 1500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private var tunnelAttempt = 0
    @Volatile private var tunnelBusy = false

    private fun startTunnel() {
        // Only one tunnel at a time — exit-retry and the network callback can both
        // fire; without this they spawn several cloudflared processes at once.
        if (tunnelBusy) return
        tunnelBusy = true
        // Kill any previous process before launching a fresh one.
        cloudflared?.stop()
        val cf = CloudflaredManager(applicationContext)
        cloudflared = cf
        if (!cf.isEmbedded()) {
            AppState.setStatus(
                "cloudflared не встроен. Используй Termux (см. README) или запусти туннель на порт ${Config.LOCAL_PORT}."
            )
            ToolLog.add("tunnel", "binary not embedded", false)
            tunnelBusy = false
            return
        }
        tunnelAttempt++
        // Resolve real edge IPs via DoH off the main thread, then launch.
        Thread {
            val edgeIps = try { DohResolver.edgeIps() } catch (e: Exception) { emptyList() }
            ToolLog.add("tunnel", "edge ips: ${edgeIps.take(3).joinToString(",")}…", true)
            startTunnelWith(cf, edgeIps)
            tunnelBusy = false
        }.start()
    }

    private fun startTunnelWith(cf: CloudflaredManager, edgeIps: List<String>) {
        if (VpnStatus.isVpnActive(applicationContext)) {
            ToolLog.add("tunnel", "VPN активен — если туннель не встанет, добавь приложение в исключения VPN", false)
        }
        scheduleVpnWarning()
        cf.start(
            localPort = Config.LOCAL_PORT,
            edgeIps = edgeIps,
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
                    val delay = (3000L * tunnelAttempt).coerceAtMost(30_000L)
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
        AppState.tunnelBase = baseUrl
        ToolLog.add("tunnel", "up: $baseUrl", true)

        val workerBase = Settings.workerBase(applicationContext)
        if (workerBase.isNotBlank()) {
            // Stable URL mode: connector points at the Worker; tell it the new backend.
            pushBackendToWorker(workerBase, baseUrl)
            val full = "$workerBase/$token/mcp"
            AppState.setConnectorUrl(full)
            AppState.setStatus("Готово. Постоянный адрес (Worker).")
            copyToClipboard(full)
            updateNotification("Коннектор готов (постоянный)", full)
        } else {
            val full = "$baseUrl/$token/mcp"
            AppState.setConnectorUrl(full)
            AppState.setStatus("Готово. Коннектор доступен.")
            copyToClipboard(full)
            updateNotification("Коннектор готов", full)
        }
    }

    /** If still no connector after 20s and a VPN is on, tell the user what to do. */
    private fun scheduleVpnWarning() {
        updateHandler.postDelayed({
            if (started && AppState.connectorUrl == null && VpnStatus.isVpnActive(applicationContext)) {
                val msg = "Включён VPN и он, похоже, режет туннель (порт 7844). Добавь это приложение в исключения VPN (split tunneling) и перезапусти сервер."
                AppState.setStatus(msg)
                ToolLog.add("tunnel", msg, false)
                notifySimple("Мешает VPN", msg)
            }
        }, 20_000)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "phonemcp:tunnel").apply {
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
                    // Network (re)appeared — if the tunnel isn't up, restart it.
                    if (started && AppState.connectorUrl == null) {
                        updateHandler.post { if (started) { tunnelAttempt = 0; startTunnel() } }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {}
    }

    /** Push the current tunnel base to the Worker proxy so its stable URL forwards here. */
    private fun pushBackendToWorker(workerBase: String, backend: String) {
        Thread {
            try {
                val conn = (java.net.URL("$workerBase/__set").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000; readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Update-Secret", Settings.workerSecret(applicationContext))
                }
                conn.outputStream.use { it.write("{\"url\":\"$backend\"}".toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                ToolLog.add("worker", if (code == 200) "backend обновлён" else "ошибка обновления: HTTP $code", code == 200)
            } catch (e: Exception) {
                ToolLog.add("worker", "не достучался до воркера: ${e.message}", false)
            }
        }.start()
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
        updateHandler.removeCallbacks(workerTick)
        updateHandler.removeCallbacks(serverTick)
        try { netCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        netCallback = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
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
        const val ACTION_APPLY_WORKER = "com.phonemcp.APPLY_WORKER"
        private const val MAX_TUNNEL_ATTEMPTS = 100
    }
}
