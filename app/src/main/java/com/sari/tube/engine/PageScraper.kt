package com.sari.tube.engine

import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.UnsupportedMimeTypeException
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Outcome of checking one web page before it is offered to the user. */
enum class PageCheck {
    /** A direct video link was found and it answers like a real video. */
    PLAYABLE,

    /** The page loads, but no direct video link could be verified (empty page, article, unknown player). */
    NO_VIDEO,

    /** The page asks for a sign-in before it shows anything. */
    LOGIN_REQUIRED,

    /** The page uses a DRM player. It cannot be played inside the app. */
    DRM,

    /** The server answered with an error such as 404 or 403. */
    HTTP_ERROR,

    /** Timeout or connection failure. The page was not judged, so it is shown as unverified. */
    NETWORK_ERROR
}

/**
 * Reads the HTML of a page with Jsoup and looks for direct video addresses (.m3u8, .mpd, .mp4, .webm)
 * in video, source and meta tags, in the scripts of the page, and in its first iframe.
 * Each address is checked with a small request before it is used, so ads and dead links are skipped.
 */
object PageScraper {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_CHECKS = 3
    private const val CACHE_SIZE = 200
    private const val CACHE_TTL_MS = 15 * 60_000L

    /** Streaming services that use DRM. Their video cannot be played outside their own apps. */
    private val DRM_HOSTS = listOf(
        "netflix.com", "disneyplus.com", "primevideo.com", "hulu.com", "max.com", "hbomax.com",
        "shahid.net", "crunchyroll.com", "peacocktv.com", "paramountplus.com", "starz.com",
        "tv.apple.com", "hotstar.com", "viu.com", "iq.com"
    )

    /** Page markers of DRM players (Widevine, PlayReady, FairPlay). Such pages cannot be played. */
    private val DRM_MARKERS = listOf("widevine", "playready", "fairplay", "com.microsoft.playready")

    private val MEDIA_REGEX = Regex(
        """https?://[^\s"'<>\\]+?\.(?:m3u8|mpd|mp4|webm)(?:[?#][^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val MEDIA_EXTENSION = Regex("""\.(?:m3u8|mpd|mp4|webm)(?:[?#]|\z)""")

    /** Sign-in paths. A page under one of them is treated as login-only when it shows no video. */
    private val LOGIN_PATH = Regex("""/(?:log-?in|sign-?in|auth)(?:[/?#]|\z)""", RegexOption.IGNORE_CASE)

    private val AD_MARKERS = listOf("doubleclick", "googlesyndication", "/ads/", "/advert", "adserver")

    private data class Candidate(val url: String, val referer: String)

    private class CachedCheck(val result: PageCheck, val at: Long)

    private val cache = object : LinkedHashMap<String, CachedCheck>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedCheck>?): Boolean =
            size > CACHE_SIZE
    }

    fun isDrmSite(pageUrl: String): Boolean {
        val host = hostOf(pageUrl) ?: return false
        return DRM_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Returns the first verified direct video link on the page, or null when there is none. */
    suspend fun scrape(pageUrl: String): PlaybackStream? = withContext(Dispatchers.IO) {
        val doc = fetch(pageUrl, pageUrl) ?: return@withContext null
        val found = LinkedHashSet<Candidate>()
        collect(doc, pageUrl, found)
        addFirstFrame(doc, pageUrl, found)

        // HLS playlists first: they adapt to the connection and are the usual source of full episodes.
        val ordered = found.sortedByDescending { mediaPriority(it.url) }
        for (c in ordered.take(MAX_CHECKS)) {
            if (verify(c.url, c.referer)) {
                return@withContext PlaybackStream(
                    url = c.url,
                    headers = mapOf(
                        "Referer" to c.referer,
                        "User-Agent" to USER_AGENT,
                        "Origin" to originOf(c.referer)
                    ),
                    mimeType = mimeTypeFor(c.url),
                    height = 0,
                    label = "تلقائي"
                )
            }
        }
        null
    }

    /**
     * Checks a page for a playable video. The result is cached for a short time; network failures are
     * not cached, so a later check can still succeed.
     */
    suspend fun check(pageUrl: String): PageCheck = withContext(Dispatchers.IO) {
        cachedCheck(pageUrl)?.let { return@withContext it }
        val result = runCheck(pageUrl)
        rememberCheck(pageUrl, result)
        result
    }

    private fun runCheck(pageUrl: String): PageCheck {
        if (isDrmSite(pageUrl)) return PageCheck.DRM

        val doc = try {
            connect(pageUrl, pageUrl)
        } catch (e: UnsupportedMimeTypeException) {
            return PageCheck.NO_VIDEO
        } catch (e: HttpStatusException) {
            return if (e.statusCode == 401) PageCheck.LOGIN_REQUIRED else PageCheck.HTTP_ERROR
        } catch (e: IOException) {
            return PageCheck.NETWORK_ERROR
        } catch (e: Exception) {
            return PageCheck.NETWORK_ERROR
        }

        val html = doc.html()
        if (DRM_MARKERS.any { html.contains(it, ignoreCase = true) }) return PageCheck.DRM

        val found = LinkedHashSet<Candidate>()
        collect(doc, pageUrl, found)
        addFirstFrame(doc, pageUrl, found)
        if (found.sortedByDescending { mediaPriority(it.url) }.take(MAX_CHECKS).any { verify(it.url, it.referer) }) {
            return PageCheck.PLAYABLE
        }

        val hasPasswordField = doc.select("input[type=password]").isNotEmpty()
        val path = runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault("")
        if (hasPasswordField || LOGIN_PATH.containsMatchIn(path)) return PageCheck.LOGIN_REQUIRED
        return PageCheck.NO_VIDEO
    }

    private fun addFirstFrame(doc: Document, pageUrl: String, found: MutableSet<Candidate>) {
        // Many sites put the player in an iframe, so the first frame is read too.
        doc.select("iframe[src]").take(1).forEach { frame ->
            val src = frame.absUrl("src")
            if (src.startsWith("http")) {
                fetch(src, pageUrl)?.let { collect(it, src, found) }
            }
        }
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

    private fun connect(url: String, referer: String): Document =
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .referrer(referer)
            .timeout(6_000)
            .maxBodySize(1024 * 1024)
            .followRedirects(true)
            .get()

    private fun fetch(url: String, referer: String): Document? =
        runCatching { connect(url, referer) }.getOrNull()

    /**
     * Requests the first bytes only. An HLS file must start with #EXTM3U, and an MP4 must not be an HTML page.
     */
    private fun verify(url: String, referer: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 4_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", referer)
                setRequestProperty("Range", "bytes=0-1023")
                setRequestProperty(
                    "Accept",
                    "video/*,application/vnd.apple.mpegurl,application/x-mpegURL,application/dash+xml,*/*;q=0.8"
                )
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) return false
            val type = conn.contentType.orEmpty().lowercase()
            if (type.startsWith("text/html") || type.contains("json")) return false
            val buffer = ByteArray(64)
            val read = conn.inputStream.use { it.read(buffer) }
            if (read <= 0) return false
            val head = String(buffer, 0, read, Charsets.ISO_8859_1)
                .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            val isMp4 = read >= 8 && String(buffer, 4, 4, Charsets.US_ASCII) == "ftyp"
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

    private fun cachedCheck(url: String): PageCheck? = synchronized(cache) {
        val entry = cache[url] ?: return null
        if (System.currentTimeMillis() - entry.at > CACHE_TTL_MS) {
            cache.remove(url)
            null
        } else {
            entry.result
        }
    }

    private fun rememberCheck(url: String, result: PageCheck) {
        // Timeouts and server errors can change, so only definitive answers are kept.
        if (result == PageCheck.NETWORK_ERROR || result == PageCheck.HTTP_ERROR) return
        synchronized(cache) { cache[url] = CachedCheck(result, System.currentTimeMillis()) }
    }

    private fun isHls(url: String) = url.contains(".m3u8", ignoreCase = true)
    private fun isDash(url: String) = url.contains(".mpd", ignoreCase = true)

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return MEDIA_EXTENSION.containsMatchIn(lower) ||
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
