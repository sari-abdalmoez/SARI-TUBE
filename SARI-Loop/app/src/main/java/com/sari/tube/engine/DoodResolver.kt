package com.sari.tube.engine

import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

data class ResolvedStream(val url: String, val headers: Map<String, String>)

/**
 * Kotlin port of the DoodStream logic from the original script:
 * embed page -> pass_md5 path -> base URL -> append random suffix, token and expiry.
 * Returns null on any failure so the caller can fall back to another candidate.
 * Cookies from the server are kept and sent with the stream request.
 */
object DoodResolver {
    private val DOMAINS = listOf("dood.to", "dood.so", "dood.ws", "doodstream.com", "dood.re")
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private const val CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val ID_IN_PATH = Regex("""/(?:d|e)/([A-Za-z0-9]+)""")
    private val ID_ONLY = Regex("""[A-Za-z0-9]{8,15}""")
    private val PASS_MD5 = Regex("""(/pass_md5/[^'"\s]+)""")

    fun isDoodUrl(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrDefault("")
        return DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    fun extractId(input: String): String? {
        ID_IN_PATH.find(input)?.let { return it.groupValues[1] }
        val trimmed = input.trim()
        return if (ID_ONLY.matches(trimmed)) trimmed else null
    }

    fun resolve(input: String): ResolvedStream? {
        val id = extractId(input) ?: return null
        val cookies = LinkedHashMap<String, String>()
        for (domain in DOMAINS) {
            try {
                val pageUrl = "https://$domain/e/$id"
                val page = get(pageUrl, mapOf("User-Agent" to UA), cookies)
                if (page.code != 200) continue
                val path = PASS_MD5.find(page.body)?.groupValues?.get(1) ?: continue
                val token = path.substringAfterLast('/')
                val base = get("https://$domain$path", mapOf("User-Agent" to UA, "Referer" to pageUrl), cookies)
                    .body.trim()
                if (!base.startsWith("http")) continue

                val random = (1..10).map { CHARS[Random.nextInt(CHARS.length)] }.joinToString("")
                val direct = "$base$random?token=$token&expiry=${System.currentTimeMillis()}"

                val headers = mutableMapOf("User-Agent" to UA, "Referer" to pageUrl)
                if (cookies.isNotEmpty()) {
                    headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                }
                return ResolvedStream(direct, headers)
            } catch (e: Exception) {
                // Try the next domain.
            }
        }
        return null
    }

    private class Page(val code: Int, val body: String)

    private fun get(url: String, headers: Map<String, String>, cookies: MutableMap<String, String>): Page {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (cookies.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            conn.connect()
            conn.headerFields.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value ?: emptyList() }
                .forEach { raw ->
                    val pair = raw.substringBefore(';')
                    val key = pair.substringBefore('=').trim()
                    if (key.isNotEmpty()) cookies[key] = pair.substringAfter('=', "")
                }
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
            return Page(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
