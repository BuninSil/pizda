package com.phonemcp.tunnel

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Resolves Cloudflare edge addresses via DNS-over-HTTPS (1.1.1.1 on port 443),
 * bypassing the device/VPN DNS. A VPN in fake-IP mode hands cloudflared bogus
 * 198.18.0.0/15 addresses for the edge, killing the tunnel; DoH returns the real
 * IPs, which we pass to cloudflared via --edge so it connects to the real edge.
 */
object DohResolver {

    private val EDGE_HOSTS = listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")

    // Documented Cloudflare tunnel edge IPs, used if DoH is unreachable.
    private val FALLBACK = listOf(
        "198.41.192.7", "198.41.192.27", "198.41.192.37", "198.41.192.47",
        "198.41.192.67", "198.41.192.77", "198.41.192.107", "198.41.192.167",
        "198.41.200.13", "198.41.200.23", "198.41.200.33", "198.41.200.43",
        "198.41.200.53", "198.41.200.63", "198.41.200.113", "198.41.200.193"
    )

    /** Real edge IPv4s (DoH first, fallback to the documented list). */
    fun edgeIps(): List<String> {
        val ips = LinkedHashSet<String>()
        for (h in EDGE_HOSTS) ips.addAll(resolveA(h))
        val real = ips.filterNot { isFake(it) }
        return if (real.isNotEmpty()) real else FALLBACK
    }

    /** 198.18.0.0/15 is the VPN fake-IP pool — never a real edge. */
    private fun isFake(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return true
        return p[0] == 198 && (p[1] == 18 || p[1] == 19)
    }

    private fun resolveA(host: String): List<String> {
        return try {
            val conn = (URL("https://1.1.1.1/dns-query?name=$host&type=A").openConnection()
                as HttpsURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/dns-json")
                setRequestProperty("User-Agent", "PhoneMCP")
            }
            if (conn.responseCode != 200) { conn.disconnect(); return emptyList() }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                if (a.optInt("type") == 1) a.optString("data").takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
