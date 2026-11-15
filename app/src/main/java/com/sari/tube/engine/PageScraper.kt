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
    private const val MAX_CHECKS = 10

    /** Streaming services that use DRM. Their video cannot be played outside their own apps. */
    private val DRM_HOSTS = listOf(
        "netflix.com", "disneyplus.com", "primevideo.com", "hulu.com", "max.com", "hbomax.com",
        "shahid.net", "crunchyroll.com", "peacocktv.com", "paramountplus.com", "starz.com",
        "tv.apple.com", "hotstar.com", "viu.com", "iq.com"
    )

    private val MEDIA_REGEX = Regex(
        """https?://[^\s"'<>\\]+?\.(?:m3u8|mpd|mp4|webm)(?:[?#][^\s"'<>\\]*)?""",
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
        val ordered = found.sortedByDescending { mediaPriority(it.url) }
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
                    mimeType = mimeTypeFor(c.url),
                    height = 0,
                    label = "تلقائي"
                )
            }
        }
        null
    }

    /** Page markers of DRM players (Widevine, PlayReady, FairPlay). Such pages cannot be played, so they are skipped. */
    private val DRM_MARKERS = listOf("widevine", "playready", "fairplay", "com.microsoft.playready")

    /**
     * True when the page can give a playable video: it is not DRM-protected and has a verified direct link.
     * Used to filter web search results, so pages with no video are not offered.
     */
    suspend fun hasPlayableVideo(pageUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (isDrmSite(pageUrl)) return@withContext false
        val doc = fetch(pageUrl, pageUrl) ?: return@withContext false
        val html = doc.html().lowercase()
        if (DRM_MARKERS.any { html.contains(it) }) return@withContext false

        // Reuse the downloaded document instead of downloading the same page again.
        val found = LinkedHashSet<Candidate>()
        collect(doc, pageUrl, found)
        doc.select("iframe[src]").take(3).forEach { frame ->
            val src = frame.absUrl("src")
            if (src.startsWith("http")) {
                fetch(src, pageUrl)?.let { collect(it, src, found) }
            }
        }
        found.sortedByDescending { mediaPriority(it.url) }
            .take(MAX_CHECKS)
            .any { verify(it.url, it.referer) }
    }

    private fun collect(doc: Document, base: String, out: MutableSet<Candidate>) {
        // Attributes that name a media file.
        doc.select("video[src], video source[src], source[src], audio[src], [data-src], [data-hls], [data-dash], [data-file], [data-video]")
            .forEach { el ->
                listOf("src", "data-src", "data-hls", "data-dash", "data-file", "data-video")
                    .forEach { attribute ->
                        val raw = el.attr(attribute)
                        if (raw.isNotBlank()) addIfMedia(el.absUrl(attribute).ifBlank { raw }, base, out)
                    }
            }
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player:stream]")
            .forEach { el -> addIfMedia(el.absUrl("content"), base, out) }
        doc.select("a[href]").forEach { el ->
            val href = el.absUrl("href")
            if (isMediaUrl(href)) addIfMedia(href, base, out)
        }

        // Scripts often hold the address the player will load. Escaped slashes are restored first.
        val text = doc.html()
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&")
            .replace("\\u003d", "=", ignoreCase = true)
        MEDIA_REGEX.findAll(text).forEach {
            addIfMedia(it.value.trimEnd(')', ']', ',', ';'), base, out)
        }
    }

    private fun addIfMedia(url: String, base: String, out: MutableSet<Candidate>) {
        if (!url.startsWith("http", ignoreCase = true) || !isMediaUrl(url)) return
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
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "Accept",
                "video/*,application/vnd.apple.mpegurl,application/x-mpegURL,application/dash+xml,*/*;q=0.8"
            )
            val code = conn.responseCode
            if (code !in 200..299) return false
            val type = conn.contentType.orEmpty().lowercase()
            if (type.startsWith("text/html") || type.contains("json")) return false
            val buffer = ByteArray(64)
            val read = conn.inputStream.use { it.read(buffer) }
            if (read <= 0) return false
            val head = String(buffer, 0, read, Charsets.ISO_8859_1)
                .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            val isMp4 = read >= 8 &&
                String(buffer, 4, 4, Charsets.US_ASCII) == "ftyp"
            val isWebm = read >= 4 &&
                buffer[0] == 0x1A.toByte() &&
                buffer[1] == 0x45.toByte() &&
                buffer[2] == 0xDF.toByte() &&
                buffer[3] == 0xA3.toByte()
            when {
                isHls(url) || type.contains("mpegurl") -> head.startsWith("#EXTM3U")
                isDash(url) || type.contains("dash+xml") -> head.contains("<MPD", ignoreCase = true)
                type.startsWith("video/") -> true
                isMp4 || isWebm -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun isHls(url: String) = url.contains(".m3u8", ignoreCase = true)
    private fun isDash(url: String) = url.contains(".mpd", ignoreCase = true)
    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return Regex("""\.(m3u8|mpd|mp4|webm)(?:[?#]|$)""").containsMatchIn(lower) ||
            lower.contains("format=m3u8") ||
            lower.contains("type=application/vnd.apple.mpegurl") ||
            lower.contains("type=application/dash+xml")
    }
    private fun mediaPriority(url: String): Int = when {
        isHls(url) -> 4
        isDash(url) -> 3
        url.contains(".mp4", true) -> 2
        else -> 1
    }
    private fun mimeTypeFor(url: String): String? = when {
        isHls(url) -> MimeTypes.APPLICATION_M3U8
        isDash(url) -> MimeTypes.APPLICATION_MPD
        else -> null
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    private fun originOf(url: String): String =
        runCatching {
            val u = URI(url)
            "${u.scheme}://${u.host}"
        }.getOrDefault("")
}
