package com.phonemcp.tunnel

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * Runs the embedded cloudflared binary (shipped as a native lib so it lands on
 * disk with the exec bit) to open a free Cloudflare Quick Tunnel — no account,
 * no domain. Parses the https://<random>.trycloudflare.com URL from its output.
 */
class CloudflaredManager(private val context: Context) {

    @Volatile private var process: Process? = null
    @Volatile private var readerThread: Thread? = null

    private val urlPattern: Pattern =
        Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com")

    fun binaryFile(): File =
        File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")

    fun isEmbedded(): Boolean = binaryFile().exists()

    /**
     * Starts the tunnel to the given local port.
     * @param onUrl called with the public base URL once detected.
     * @param onLog called with each output line (for debugging / status).
     * @param onExit called if the process dies.
     */
    fun start(
        localPort: Int,
        onUrl: (String) -> Unit,
        onLog: (String) -> Unit,
        onExit: (Int) -> Unit
    ) {
        val bin = binaryFile()
        if (!bin.exists()) {
            onLog("cloudflared binary not embedded (libcloudflared.so missing)")
            onExit(-1)
            return
        }
        bin.setExecutable(true, false)

        val pb = ProcessBuilder(
            bin.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--url", "http://127.0.0.1:$localPort"
        )
        pb.redirectErrorStream(true)
        pb.directory(context.cacheDir)
        // cloudflared keeps config/logs here.
        pb.environment()["HOME"] = context.cacheDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath

        val proc = pb.start()
        process = proc

        val t = Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line = reader.readLine()
                    var reported = false
                    while (line != null) {
                        onLog(line)
                        if (!reported) {
                            val m = urlPattern.matcher(line)
                            if (m.find()) {
                                reported = true
                                onUrl(m.group())
                            }
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                onLog("reader error: ${e.message}")
            } finally {
                val code = try { proc.waitFor() } catch (e: Exception) { -1 }
                onExit(code)
            }
        }
        t.isDaemon = true
        t.start()
        readerThread = t
    }

    fun stop() {
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        readerThread = null
    }
}
