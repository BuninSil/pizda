package com.phonemcp.tunnel

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * Runs the embedded cloudflared binary (shipped as a native lib so it lands on
 * disk with the exec bit) to open a free Cloudflare Quick Tunnel — no account,
 * no domain. Parses the https://<random-words>.trycloudflare.com URL from its
 * output.
 */
class CloudflaredManager(private val context: Context) {

    @Volatile private var process: Process? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile var stopped: Boolean = false
        private set

    // The real quick-tunnel host is a multi-word subdomain like
    // "great-blue-sky-1234.trycloudflare.com". cloudflared also prints the
    // service host "api.trycloudflare.com" during registration — exclude it with
    // a negative lookahead so we don't latch onto the wrong URL.
    private val urlPattern: Pattern =
        Pattern.compile("https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com")

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
        edgeIps: List<String>,
        onUrl: (String) -> Unit,
        onLog: (String) -> Unit,
        onExit: (Int) -> Unit
    ) {
        stopped = false
        val bin = binaryFile()
        if (!bin.exists()) {
            onLog("cloudflared binary not embedded (libcloudflared.so missing)")
            onExit(-1)
            return
        }
        bin.setExecutable(true, false)

        val args = arrayListOf(
            bin.absolutePath,
            "tunnel",
            "--no-autoupdate",
            // HTTP2 (TCP) edge protocol — QUIC (UDP:7844) is often blocked.
            "--protocol", "http2",
            "--edge-ip-version", "4",
            "--loglevel", "info"
        )
        // Pin the edge to real IPs resolved via DoH, so a fake-IP VPN can't point
        // cloudflared at a dead 198.18.x.x address.
        if (edgeIps.isNotEmpty()) {
            args.add("--edge")
            args.add(edgeIps.joinToString(",") { "$it:7844" })
        }
        args.add("--url"); args.add("http://127.0.0.1:$localPort")
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        pb.directory(context.cacheDir)
        // cloudflared keeps config/logs here.
        pb.environment()["HOME"] = context.cacheDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            onLog("cloudflared failed to start: ${e.message}")
            onExit(-1)
            return
        }
        process = proc

        val t = Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line = reader.readLine()
                    var reported = false
                    while (line != null) {
                        // Surface meaningful lines (errors, connection state, the URL)
                        // so the user can diagnose a failing tunnel from the app log.
                        if (isInteresting(line)) onLog(trimLine(line))
                        if (!reported) {
                            val m = urlPattern.matcher(line)
                            if (m.find()) {
                                reported = true
                                onUrl(m.group())
                            } else if (isDnsFailure(line)) {
                                // Only a real problem if we never get a tunnel URL. cloudflared
                                // also does non-fatal feature lookups (cfd-features.argotunnel.com)
                                // that can fail via [::1]:53 while the tunnel comes up fine.
                                onLog("note: DNS lookup via [::1]:53 failed (ок, если туннель всё равно поднялся).")
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

    private fun isInteresting(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("err") || l.contains("fail") || l.contains("fatal") ||
            l.contains("trycloudflare.com") || l.contains("registered") ||
            l.contains("connection") || l.contains("unregistered") ||
            l.contains("retry") || l.contains("protocol")
    }

    private fun isDnsFailure(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("[::1]:53") || l.contains("127.0.0.1:53") ||
            l.contains("no such host") || l.contains("server misbehaving") ||
            (l.contains("lookup") && l.contains("53"))
    }

    private fun trimLine(line: String): String =
        line.replace(Regex("\\s+"), " ").trim().take(160)

    fun stop() {
        stopped = true
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        readerThread = null
    }
}
