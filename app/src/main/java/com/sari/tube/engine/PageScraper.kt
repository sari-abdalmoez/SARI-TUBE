package com.sari.tube.engine

import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Reads the HTML of a page with Jsoup and looks for direct video addresses (.m3u8 or .mp4) in:
 * video, source and meta tags, in the scripts of the page, and in the pages of its iframes.
 * Each address is checked with a small request before it is used, so ads and dead links are skipped.
 */
object PageScraper {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_CHECKS = 6

    /** Streaming services that use DRM. Their video cannot be played outside their own apps. */
    private val DRM_HOSTS = listOf(
        "netflix.com", "disneyplus.com", "primevideo.com", "hulu.com", "max.com", "hbomax.com",
        "shahid.net", "crunchyroll.com", "peacocktv.com", "paramountplus.com", "starz.com",
        "tv.apple.com", "hotstar.com", "viu.com", "iq.com"
    )

    private val MEDIA_REGEX = Regex(
        """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:[?#][^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )

    private val AD_MARKERS = listOf("doubleclick", "googlesyndication", "/ads/", "/advert", "adserver")

    private data class Candidate(val url: String, val referer: String)

    fun isDrmSite(pageUrl: String): Boolean {
        val host = hostOf(pageUrl) ?: return false
        return DRM_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Returns the first verified direct video link on the page, or null when there is none. */
    suspend fun scrape(pageUrl: String): PlaybackStream? = withContext(Dispatchers.IO) {
        val doc = fetch(pageUrl, pageUrl) ?: return@withContext null
        val found = LinkedHashSet<Candidate>()
        collect(doc, pageUrl, found)

        // Many sites put the player in an iframe, so the first few frames are read too.
        doc.select("iframe[src]").take(3).forEach { frame ->
            val src = frame.absUrl("src")
            if (src.startsWith("http")) {
                fetch(src, pageUrl)?.let { collect(it, src, found) }
            }
        }

        // HLS playlists first: they adapt to the connection and are the usual source of full episodes.
        val ordered = found.sortedByDescending { if (it.url.contains(".m3u8", ignoreCase = true)) 1 else 0 }
        for (c in ordered.take(MAX_CHECKS)) {
            if (verify(c.url, c.referer)) {
                val origin = originOf(c.referer)
                return@withContext PlaybackStream(
                    url = c.url,
                    headers = mapOf(
                        "Referer" to c.referer,
                        "User-Agent" to USER_AGENT,
                        "Origin" to origin
                    ),
                    mimeType = if (c.url.contains(".m3u8", ignoreCase = true)) MimeTypes.APPLICATION_M3U8 else null,
                    height = 0,
                    label = "تلقائي"
                )
            }
        }
        null
    }

    private fun collect(doc: Document, base: String, out: MutableSet<Candidate>) {
        // Attributes that name a media file.
        doc.select("video[src], video source[src], source[src], audio[src]").forEach { el ->
            addIfMedia(el.absUrl("src"), base, out)
        }
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player:stream]")
            .forEach { el -> addIfMedia(el.absUrl("content"), base, out) }
        doc.select("a[href]").forEach { el ->
            val href = el.absUrl("href")
            if (href.endsWith(".mp4", true) || href.endsWith(".m3u8", true)) addIfMedia(href, base, out)
        }

        // Scripts often hold the address the player will load. Escaped slashes are restored first.
        val text = doc.html().replace("\\/", "/").replace("&amp;", "&")
        MEDIA_REGEX.findAll(text).forEach { addIfMedia(it.value, base, out) }
    }

    private fun addIfMedia(url: String, base: String, out: MutableSet<Candidate>) {
        if (!url.startsWith("http", ignoreCase = true)) return
        if (AD_MARKERS.any { url.contains(it, ignoreCase = true) }) return
        out += Candidate(url, base)
    }

    private fun fetch(url: String, referer: String): Document? = runCatching {
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .referrer(referer)
            .timeout(15_000)
            .followRedirects(true)
            .get()
    }.getOrNull()

    /**
     * Requests the first bytes only. An HLS file must start with #EXTM3U, and an MP4 must not be an HTML page.
     */
    private fun verify(url: String, referer: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", referer)
                setRequestProperty("Range", "bytes=0-1023")
            }
            val code = conn.responseCode
            if (code !in 200..299) return false
            val type = conn.contentType.orEmpty().lowercase()
            if (type.startsWith("text/html")) return false
            val buffer = ByteArray(16)
            val read = conn.inputStream.use { it.read(buffer) }
            if (read <= 0) return false
            val head = String(buffer, 0, read, Charsets.ISO_8859_1)
            if (url.contains(".m3u8", ignoreCase = true)) head.startsWith("#EXTM3U") else !head.trimStart().startsWith("<")
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    private fun originOf(url: String): String =
        runCatching {
            val u = URI(url)
            "${u.scheme}://${u.host}"
        }.getOrDefault("")
}
