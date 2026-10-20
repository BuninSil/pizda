package com.phonemcp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.phonemcp.BuildConfig
import com.phonemcp.core.ToolLog
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater: checks the latest GitHub release, compares its versionCode
 * (encoded in the tag, e.g. "v42") to this build, downloads the APK and hands
 * it to the system installer.
 *
 * Works only if the repo (or a dedicated releases repo) is PUBLIC — the GitHub
 * API and asset download are unauthenticated here on purpose (no token shipped
 * in the app). See README for the private-repo workaround.
 */
object UpdateManager {

    data class UpdateInfo(val versionCode: Int, val tag: String, val apkUrl: String, val notes: String)

    /** Explicit outcome so the UI never confuses "up to date" with "check failed". */
    sealed class Outcome {
        data class Available(val info: UpdateInfo) : Outcome()
        data class UpToDate(val latest: Int) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    private val apiUrl = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    /** Blocking — call from a worker thread. */
    fun check(): Outcome {
        return try {
            val (code, body) = httpGet(apiUrl)
            if (code != 200 || body == null) {
                val reason = when (code) {
                    403 -> "GitHub API лимит/доступ (403). Повтори позже."
                    404 -> "релиз не найден (404). Репозиторий публичный?"
                    -1 -> "нет сети или не достучаться до GitHub"
                    else -> "GitHub ответил $code"
                }
                ToolLog.add("update", "check failed: $reason", false)
                return Outcome.Failed(reason)
            }
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name")
            val latestCode = tag.filter { it.isDigit() }.toIntOrNull()
                ?: return Outcome.Failed("не разобрал тег релиза '$tag'")
            if (latestCode <= BuildConfig.VERSION_CODE) return Outcome.UpToDate(latestCode)

            val assets = obj.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }
            if (apkUrl.isNullOrBlank()) return Outcome.Failed("в релизе $tag нет .apk")
            Outcome.Available(UpdateInfo(latestCode, tag, apkUrl, obj.optString("body").take(500)))
        } catch (e: Exception) {
            ToolLog.add("update", "check failed: ${e.message}", false)
            Outcome.Failed(e.message ?: "ошибка проверки")
        }
    }

    /** Blocking download of the APK into cacheDir/updates. */
    fun download(context: Context, info: UpdateInfo): File? {
        return try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clean old files.
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "phone-mcp-${info.versionCode}.apk")

            var url = info.apkUrl
            var redirects = 0
            while (redirects < 5) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "PhoneMCP")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    url = conn.getHeaderField("Location") ?: return null
                    conn.disconnect()
                    redirects++
                    continue
                }
                if (code != 200) { conn.disconnect(); return null }
                conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                return out
            }
            null
        } catch (e: Exception) {
            ToolLog.add("update", "download failed: ${e.message}", false)
            null
        }
    }

    /** Launches the system package installer for the downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun currentVersionLabel(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** @return (httpCode, body). code -1 means the request threw (no net / DNS / TLS). */
    private fun httpGet(urlStr: String): Pair<Int, String?> {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", "PhoneMCP")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                val code = conn.responseCode
                val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
                Pair(code, body)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Pair(-1, null)
        }
    }
}
