package com.phonemcp.mcp

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings

/** Device controls: volume, flashlight, settings screens, brightness, URLs. */
object SystemHelper {

    // --- Volume -----------------------------------------------------------

    fun setVolume(context: Context, stream: String, percent: Int): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = when (stream.lowercase()) {
            "media", "music" -> AudioManager.STREAM_MUSIC
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> return false
        }
        val max = am.getStreamMaxVolume(s)
        val target = (percent.coerceIn(0, 100) * max / 100.0).toInt().coerceIn(0, max)
        return try {
            am.setStreamVolume(s, target, 0)
            true
        } catch (e: SecurityException) {
            // Ring/notification volume to 0 may need DND access on some OEMs.
            false
        }
    }

    // --- Flashlight -------------------------------------------------------

    @Volatile private var torchOn = false
    @Volatile private var torchInit = false

    fun toggleFlashlight(context: Context): Boolean {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return false

        if (!torchInit) {
            torchInit = true
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id == camId) torchOn = enabled
                }
            }, handler)
        }
        return try {
            cm.setTorchMode(camId, !torchOn)
            torchOn = !torchOn
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- Settings screens -------------------------------------------------

    fun openSettings(context: Context, target: String): Boolean {
        val action = when (target.lowercase()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "data", "mobile", "cellular" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "date", "time" -> Settings.ACTION_DATE_SETTINGS
            "" , "settings", "root" -> Settings.ACTION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- Brightness (needs WRITE_SETTINGS) --------------------------------

    /** @return null on success, or an error message (e.g. missing permission). */
    fun setBrightness(context: Context, percent: Int): String? {
        if (!Settings.System.canWrite(context)) {
            // Send the user to grant it.
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
            return "нет разрешения WRITE_SETTINGS — выдай его в открывшихся настройках и повтори"
        }
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val value = (percent.coerceIn(0, 100) * 255 / 100.0).toInt().coerceIn(1, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            null
        } catch (e: Exception) {
            "не удалось выставить яркость: ${e.message}"
        }
    }

    // --- Open URL ---------------------------------------------------------

    /** @return null on success, or an error message. Only http/https allowed. */
    fun openUrl(context: Context, url: String): String? {
        val uri = try { Uri.parse(url.trim()) } catch (e: Exception) { return "некорректный URL" }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "разрешены только http/https ссылки"
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: Exception) {
            "не удалось открыть ссылку: ${e.message}"
        }
    }
}
