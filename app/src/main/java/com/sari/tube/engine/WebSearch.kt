package com.sari.tube.engine

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.Locale

/** Multi-engine web discovery with strict relevance and adult-content filtering. */
object WebSearch {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_RESULTS = 24

    // User-provided fallback sources. They are queried only after normal search returns no
    // relevant result; results still have to match the title and pass the safety filter.
    private val FALLBACK_SITES = listOf(
        "latino.solo-latino.com",
        "flixlat.com",
        "ww20.321moviesfree.com",
        "www3.dramasfree.com",
        "peliculaplay.com",
        "ww1.123flmsfree.com"
    )

    private val ADULT_HOST_MARKERS = listOf(
        "xhamster", "xvideos", "xnxx", "pornhub", "redtube", "youporn",
        "tube8", "spankbang", "eporner", "chaturbate", "bongacams",
        "stripchat", "onlyfans", "fapello", "xozilla", "motherless"
    )
    private val ADULT_TEXT_MARKERS = listOf(
        "porn", "porno", "xxx", "adult video", "adult videos",
        "sex video", "sex videos", "nude", "nudity", "hentai"
    )
    private val STOP_WORDS = setOf(
        "watch", "online", "full", "movie", "movies", "film", "films",
        "series", "stream", "streaming", "free", "download", "episode",
        "episodes", "season", "مشاهدة", "مشاهده", "اون", "أون", "لاين",
        "فيلم", "افلام", "أفلام", "مسلسل", "مسلسلات", "مترجم", "مدبلج",
        "كامل", "الحلقة", "الحلقات", "جميع", "اونلاين"
    )

    private data class SearchHit(val item: VideoItem, val context: String)

    suspend fun search(query: String, site: String? = null): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) throw SearchException("اكتب اسم الفيلم أو المسلسل أولاً.")

            val scoped = when (site) {
                "tiktok" -> "site:tiktok.com $clean"
                "facebook" -> "site:facebook.com $clean"
                else -> clean
            }
            // Avoid broad generic phrases such as "full movie online" that introduce unrelated pages.
            val primaryQueries = listOf(scoped, "\"$clean\" watch online").distinct()
            val normalResults = ranked(clean, collect(primaryQueries))
            if (normalResults.isNotEmpty()) return@withContext normalResults.take(MAX_RESULTS)

            // Search the supplied source domains only as a fallback, and never return their
            // home pages unless their result title/path actually relates to the requested title.
            if (site == null) {
                val siteQueries = FALLBACK_SITES.map { "site:$it \"$clean\"" }
                val fallback = ranked(clean, collect(siteQueries))
                if (fallback.isNotEmpty()) return@withContext fallback.take(MAX_RESULTS)
            }

            throw SearchException(
                "لم نعثر على صفحات مرتبطة بهذا العنوان. جرّب كتابة الاسم بالإنجليزية أو ابحث لاحقاً."
            )
        }

    /** Defense-in-depth check used again by the watch-results screen. */
    fun isRelevantAndSafe(query: String, item: VideoItem): Boolean {
        if (isAdult(item, "")) return false
        return isRelevant(query, item, "")
    }

    private suspend fun collect(queries: List<String>): List<SearchHit> = coroutineScope {
        queries.flatMap { q ->
            listOf(
                async(Dispatchers.IO) { searchDuckDuckGo(q) },
                async(Dispatchers.IO) { searchBing(q) }
            )
        }.awaitAll().flatten()
    }

    private fun ranked(query: String, hits: List<SearchHit>): List<VideoItem> {
        val seen = HashSet<String>()
        return hits.asSequence()
            .filter { isRelevantAndSafe(query, it.item) && !isAdult(it.item, it.context) }
            .filter { seen.add(it.item.url.substringBefore('#').trimEnd('/')) }
            .map { it.item }
            .toList()
    }

    private fun isAdult(item: VideoItem, context: String): Boolean {
        val host = runCatching { Uri.parse(item.url).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        if (ADULT_HOST_MARKERS.any { host.contains(it) }) return true
        val text = item.title + " " + item.channel + " " + item.url + " " + context
        return ADULT_TEXT_MARKERS.any { text.contains(it, ignoreCase = true) }
    }

    private fun isRelevant(query: String, item: VideoItem, context: String): Boolean {
        val wanted = tokens(query)
        if (wanted.isEmpty()) return false

        val uri = Uri.parse(item.url)
        val pageText = item.title + " " + uri.path.orEmpty() + " " +
            uri.query.orEmpty().replace('+', ' ') + " " + context
        val available = tokens(pageText).toHashSet()
        val matches = wanted.count { it in available }
        // Require both words for a two-word title, or at least two words for longer titles.
        // A one-word title must match that word. Host names are intentionally not used as a match.
        val required = minOf(2, wanted.size)
        return matches >= required
    }

    private fun tokens(value: String): List<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(value.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { token ->
                token.length >= 2 &&
                    !token.all { it.isDigit() } &&
                    token !in STOP_WORDS
            }
            .distinct()
            .toList()

    private fun searchDuckDuckGo(query: String): List<SearchHit> = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect("https://html.duckduckgo.com/html/?q=$encoded")
            .userAgent(USER_AGENT)
            .referrer("https://duckduckgo.com/")
            .timeout(9_000)
            .maxBodySize(2 * 1024 * 1024)
            .get()

        val out = ArrayList<SearchHit>()
        doc.select("a.result__a, .result__title a").forEach { a ->
            val url = unwrap(a.attr("href").trim())
            val title = a.text().trim()
            val host = Uri.parse(url).host.orEmpty()
            val row = a.closest(".result")
            val snippet = row?.selectFirst(".result__snippet")?.text().orEmpty()
            if (isHttp(url) && host.isNotBlank() &&
                !host.contains("duckduckgo.com") && title.isNotBlank()
            ) {
                out += SearchHit(item(title, host, url), snippet)
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun searchBing(query: String): List<SearchHit> = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = Jsoup.connect("https://www.bing.com/search?format=rss&q=$encoded")
            .userAgent(USER_AGENT)
            .referrer("https://www.bing.com/")
            .timeout(9_000)
            .maxBodySize(2 * 1024 * 1024)
            .ignoreContentType(true)
            .execute()
            .body()

        val out = ArrayList<SearchHit>()
        if (body.contains("<rss", true) || body.contains("<item", true)) {
            val xml = Jsoup.parse(body, "", Parser.xmlParser())
            xml.select("item").forEach { node ->
                val title = node.selectFirst("title")?.text().orEmpty().trim()
                val url = node.selectFirst("link")?.text().orEmpty().trim()
                val host = Uri.parse(url).host.orEmpty()
                val snippet = node.selectFirst("description")?.text().orEmpty()
                if (title.isNotBlank() && isHttp(url) && host.isNotBlank()) {
                    out += SearchHit(item(title, host, url), snippet)
                }
            }
        }

        if (out.isEmpty()) {
            val html = Jsoup.parse(body)
            html.select("li.b_algo h2 a, h2 a").forEach { a ->
                val url = unwrap(a.attr("href").trim())
                val title = a.text().trim()
                val host = Uri.parse(url).host.orEmpty()
                val row = a.closest("li.b_algo")
                val snippet = row?.selectFirst("p")?.text().orEmpty()
                if (title.isNotBlank() && isHttp(url) &&
                    host.isNotBlank() && !host.contains("bing.com")
                ) {
                    out += SearchHit(item(title, host, url), snippet)
                }
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun unwrap(raw: String): String {
        if (raw.isBlank()) return ""
        val absolute = if (raw.startsWith("//")) "https:$raw" else raw
        val parsed = Uri.parse(absolute)
        return (
            parsed.getQueryParameter("uddg")
                ?: parsed.getQueryParameter("url")
                ?: absolute
            ).trim()
    }

    private fun isHttp(url: String): Boolean =
        url.startsWith("https://", true) || url.startsWith("http://", true)

    private fun item(title: String, host: String, url: String) = VideoItem(
        id = "web_" + url.hashCode(),
        title = title,
        channel = host.removePrefix("www."),
        durationSec = 0L,
        thumbnail = null,
        url = url,
        views = null
    )
}
