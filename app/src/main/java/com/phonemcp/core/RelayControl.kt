package com.phonemcp.core

import com.phonemcp.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small HTTP client for the relay Worker's control endpoints
 * (/<token>/__laptop_toggle and /<token>/__status). Used by the UI to flip the
 * laptop toggle and show whether the laptop is online. All calls are blocking —
 * run them off the main thread.
 */
object RelayControl {

    data class Status(
        val phoneConnected: Boolean,
        val laptopConnected: Boolean,
        val laptopEnabled: Boolean,
        val laptopPingAgoSec: Int?,
    )

    private fun base(context: android.content.Context): String {
        val s = Settings.workerBase(context)
        return (if (s.isNotBlank()) s else BuildConfig.RELAY_WORKER).trim().trimEnd('/')
    }

    /** POST the toggle. Returns true on HTTP 200. */
    fun setLaptopEnabled(context: android.content.Context, enabled: Boolean): Boolean {
        val token = TokenManager.getToken(context)
        return try {
            val conn = (URL("${base(context)}/$token/__laptop_toggle").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000; readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Update-Secret", BuildConfig.RELAY_SECRET)
            }
            conn.outputStream.use { it.write("{\"enabled\":$enabled}".toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            ToolLog.add("laptop", if (code == 200) "тумблер → $enabled" else "toggle HTTP $code", code == 200)
            code == 200
        } catch (e: Exception) {
            ToolLog.add("laptop", "toggle error: ${e.message}", false)
            false
        }
    }

    /** GET the status of both devices, or null if unreachable. */
    fun status(context: android.content.Context): Status? {
        val token = TokenManager.getToken(context)
        return try {
            val conn = (URL("${base(context)}/$token/__status").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000; readTimeout = 12000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(body)
            val phone = o.optJSONObject("phone") ?: JSONObject()
            val laptop = o.optJSONObject("laptop") ?: JSONObject()
            Status(
                phoneConnected = phone.optBoolean("connected", false),
                laptopConnected = laptop.optBoolean("connected", false),
                laptopEnabled = laptop.optBoolean("enabled", true),
                laptopPingAgoSec = if (laptop.isNull("lastPingAgoSec")) null else laptop.optInt("lastPingAgoSec"),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** The JSON config the laptop agent expects at ~/.config/ruki-laptop.json. */
    fun laptopConfigJson(context: android.content.Context): String {
        val token = TokenManager.getToken(context)
        return JSONObject()
            .put("url", base(context))
            .put("token", token)
            .put("secret", BuildConfig.RELAY_SECRET)
            .toString(2)
    }
}
