package com.phonemcp.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.text.InputType
import com.phonemcp.R
import com.phonemcp.core.AppState
import com.phonemcp.core.Blacklist
import com.phonemcp.core.Settings as Prefs
import com.phonemcp.core.TokenManager
import com.phonemcp.core.ToolLog
import com.phonemcp.service.McpForegroundService
import com.phonemcp.update.UpdateManager
import com.phonemcp.voice.VoiceButtonMemory

class MainActivity : Activity(), AppState.Listener {

    private lateinit var masterSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var connectorUrl: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        masterSwitch = findViewById(R.id.masterSwitch)
        statusText = findViewById(R.id.statusText)
        connectorUrl = findViewById(R.id.connectorUrl)
        logText = findViewById(R.id.logText)

        masterSwitch.isChecked = AppState.running
        masterSwitch.setOnClickListener {
            if (masterSwitch.isChecked) startServer() else stopServer()
        }

        findViewById<Button>(R.id.copyButton).setOnClickListener { copyUrl() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { requestIgnoreBattery() }
        findViewById<Button>(R.id.newTokenButton).setOnClickListener { newToken() }
        findViewById<Button>(R.id.blacklistButton).setOnClickListener { editBlacklist() }
        findViewById<Button>(R.id.callClaudeButton).setOnClickListener { callClaude() }
        findViewById<Button>(R.id.voiceSettingsButton).setOnClickListener { voiceSettings() }
        findViewById<Button>(R.id.updateButton).setOnClickListener { checkUpdates(manual = true) }
        findViewById<TextView>(R.id.versionText).text = "Версия: ${UpdateManager.currentVersionLabel()}"

        requestNotificationPermissionIfNeeded()

        // Autostart: raise server + tunnel, then call Claude, if enabled.
        if (Prefs.callClaudeOnLaunch(this)) {
            callClaude()
        }

        // Check for updates on launch (silent if none).
        checkUpdates(manual = false)
    }

    private fun checkUpdates(manual: Boolean) {
        if (manual) Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
        Thread {
            val info = UpdateManager.check()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (info == null) {
                    if (manual) Toast.makeText(this, "У тебя последняя версия", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Есть обновление (${info.tag})")
                    .setMessage(info.notes.ifBlank { "Доступна новая версия. Обновить сейчас?" })
                    .setPositiveButton("Обновить") { _, _ ->
                        val intent = Intent(this, McpForegroundService::class.java)
                            .setAction(McpForegroundService.ACTION_UPDATE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                        else startService(intent)
                        Toast.makeText(this, "Скачиваю и открою установщик…", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Позже", null)
                    .show()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(this)
        render()
    }

    override fun onPause() {
        AppState.removeListener(this)
        super.onPause()
    }

    override fun onStateChanged() = render()

    private fun render() {
        masterSwitch.isChecked = AppState.running
        statusText.text = AppState.status
        val url = AppState.connectorUrl
        connectorUrl.text = url ?: "— туннель ещё не поднят —"
        logText.text = ToolLog.formatted()
    }

    private fun startServer() {
        val intent = Intent(this, McpForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopServer() {
        stopService(Intent(this, McpForegroundService::class.java))
    }

    private fun copyUrl() {
        val url = AppState.connectorUrl
        if (url == null) {
            Toast.makeText(this, "Ссылка ещё не готова", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MCP connector URL", url))
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    private fun newToken() {
        AlertDialog.Builder(this)
            .setTitle("Новый токен")
            .setMessage("Старая ссылка перестанет работать. Коннектор в Claude нужно будет обновить. Продолжить?")
            .setPositiveButton("Да") { _, _ ->
                TokenManager.regenerate(this)
                Toast.makeText(this, "Токен обновлён. Перезапусти сервер.", Toast.LENGTH_LONG).show()
                if (AppState.running) {
                    stopServer()
                    connectorUrl.postDelayed({ startServer() }, 1500)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun editBlacklist() {
        val current = Blacklist.get(this).sorted().joinToString("\n")
        val input = EditText(this).apply {
            setText(current)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Чёрный список (по одному package на строку)")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val set = input.text.toString()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                Blacklist.set(this, set)
                Toast.makeText(this, "Сохранено (${set.size})", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun callClaude() {
        // Starts (or reuses) the service and triggers the voice call sequence.
        val intent = Intent(this, McpForegroundService::class.java)
            .setAction(McpForegroundService.ACTION_CALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        Toast.makeText(this, "Поднимаю сервер и зову Claude…", Toast.LENGTH_SHORT).show()
    }

    private fun voiceSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val timeoutLabel = TextView(this).apply { text = "Таймаут тишины (сек):" }
        val timeout = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.silenceTimeoutSec(this@MainActivity).toString())
        }
        val returnCb = CheckBox(this).apply {
            text = "Возвращаться в прежнее приложение после звонка"
            isChecked = Prefs.returnAfterStart(this@MainActivity)
        }
        val launchCb = CheckBox(this).apply {
            text = "Звать Claude при запуске приложения"
            isChecked = Prefs.callClaudeOnLaunch(this@MainActivity)
        }
        val overlayCb = CheckBox(this).apply {
            text = "Плавающая кнопка поверх приложений"
            isChecked = Prefs.overlayEnabled(this@MainActivity)
        }
        val watchdogCb = CheckBox(this).apply {
            text = "Сторожок включён (сигнал, если Claude замолчал)"
            isChecked = Prefs.watchdogEnabled(this@MainActivity)
        }
        val confirmSendsCb = CheckBox(this).apply {
            text = "Подтверждать отправки на телефоне (покупки/переводы/банк — всегда)"
            isChecked = Prefs.confirmSendsOnDevice(this@MainActivity)
        }
        val linksLabel = TextView(this).apply { text = "Deep links (по одному на строку):" }
        val links = EditText(this).apply {
            setText(Prefs.deepLinks(this@MainActivity).joinToString("\n"))
        }
        val relearn = Button(this).apply {
            text = "Переобучить кнопку голоса"
            setOnClickListener {
                VoiceButtonMemory.clear(this@MainActivity)
                VoiceButtonMemory.setLearning(this@MainActivity, true)
                Toast.makeText(this@MainActivity,
                    "Режим обучения включён. Открой Claude и нажми кнопку голоса сам.",
                    Toast.LENGTH_LONG).show()
            }
        }

        root.addView(timeoutLabel); root.addView(timeout)
        root.addView(returnCb); root.addView(launchCb); root.addView(overlayCb); root.addView(watchdogCb)
        root.addView(confirmSendsCb)
        root.addView(linksLabel); root.addView(links); root.addView(relearn)

        AlertDialog.Builder(this)
            .setTitle("Голосовой режим")
            .setView(root)
            .setPositiveButton("Сохранить") { _, _ ->
                Prefs.setSilenceTimeoutSec(this, timeout.text.toString().toIntOrNull() ?: 60)
                Prefs.setReturnAfterStart(this, returnCb.isChecked)
                Prefs.setCallClaudeOnLaunch(this, launchCb.isChecked)
                Prefs.setWatchdogEnabled(this, watchdogCb.isChecked)
                Prefs.setConfirmSendsOnDevice(this, confirmSendsCb.isChecked)
                Prefs.setDeepLinks(this, links.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() })
                applyOverlaySetting(overlayCb.isChecked)
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyOverlaySetting(enabled: Boolean) {
        if (enabled && !Settings.canDrawOverlays(this)) {
            // Need the draw-over-apps permission first.
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {}
            Toast.makeText(this, "Разреши показ поверх других приложений и включи снова", Toast.LENGTH_LONG).show()
            return
        }
        Prefs.setOverlayEnabled(this, enabled)
        val action = if (enabled) McpForegroundService.ACTION_SHOW_OVERLAY
        else McpForegroundService.ACTION_HIDE_OVERLAY
        val intent = Intent(this, McpForegroundService::class.java).setAction(action)
        if (AppState.running) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Уже отключена", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
            }
        }
    }
}
