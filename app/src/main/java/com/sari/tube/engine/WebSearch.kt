package com.sari.tube.engine

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Web results after each page was checked for a real video. */
data class WebScan(
    /** Pages with a verified direct video link. Only these are offered as playable. */
    val playable: List<VideoItem>,
    /** Pages that could not be checked in time because of the network. Shown separately, marked as unverified. */
    val uncertain: List<VideoItem>,
    /** Pages checked and rejected: empty, login required, DRM, or an error answer. */
    val rejected: Int
)

/**
 * Multi-engine web discovery. A page is only returned when it has a verified video, so empty pages,
 * sign-in pages and DRM pages are never offered as results.
 */
object WebSearch {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_RESULTS = 24

    /** Search queries that run at the same time. Each one asks two engines. */
    private const val QUERY_PARALLEL = 3

    /** Pages checked per phase, how many run at once, and the time allowed for the whole phase. */
    private const val VERIFY_LIMIT = 8
    private const val VERIFY_PARALLEL = 3
    private const val VERIFY_BUDGET_MS = 10_000L

    /** Unverified pages listed under the verified ones, so the list stays short on weak phones. */
    private const val UNCERTAIN_LIMIT = 4

    // User-provided fallback sources. They are queried only when the normal search has no page with a
    // playable video. Their results still have to match the title and pass the safety filter.
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

    /** A candidate page with how many query words its title or address matched. */
    private data class Scored(val item: VideoItem, val score: Int)

    /**
     * Searches the web and checks the pages. Pages that cannot show a video are dropped.
     * Playable pages keep the order of their match to the query.
     * Throws [SearchException] when nothing playable and nothing uncertain was found.
     */
    suspend fun searchPlayable(query: String, site: String? = null): WebScan {
        val clean = query.trim()
        if (clean.isBlank()) throw SearchException("اكتب اسم الفيلم أو المسلسل أولاً.")

        val primaryItems = rankedCandidates(clean, primaryQueries(clean, site))
        var scan = verify(primaryItems)

        // The fallback sources are used only when the normal search gives no playable page.
        if (scan.playable.isEmpty() && site == null) {
            val known = primaryItems.map { it.item.url }.toHashSet()
            val fallbackItems = rankedCandidates(
                clean,
                FALLBACK_SITES.map { "site:$it \"$clean\"" }
            ).filter { it.item.url !in known }
            val extra = verify(fallbackItems)
            scan = WebScan(
                playable = scan.playable + extra.playable,
                uncertain = scan.uncertain + extra.uncertain,
                rejected = scan.rejected + extra.rejected
            )
        }

        if (scan.playable.isEmpty() && scan.uncertain.isEmpty()) {
            throw SearchException(
                "لم نجد صفحات تحتوي فيديو قابلاً للتشغيل لهذا العنوان. جرّب كتابة الاسم بالإنجليزية أو ابحث لاحقاً."
            )
        }
        return scan
    }

    private fun primaryQueries(clean: String, site: String?): List<String> {
        val scoped = when (site) {
            "tiktok" -> "site:tiktok.com $clean"
            "facebook" -> "site:facebook.com $clean"
            else -> clean
        }
        // Avoid broad generic phrases such as "full movie online" that introduce unrelated pages.
        return listOf(scoped, "\"$clean\" watch online").distinct()
    }

    /** Checks up to [VERIFY_LIMIT] candidates in parallel within a time budget. */
    private suspend fun verify(items: List<Scored>): WebScan {
        if (items.isEmpty()) return WebScan(emptyList(), emptyList(), 0)

        val toCheck = items.take(VERIFY_LIMIT)
        val notChecked = items.drop(VERIFY_LIMIT).map { it.item }
        val statuses = ConcurrentHashMap<String, PageCheck>()
        val gate = Semaphore(VERIFY_PARALLEL)

        // Pages that finish before the budget keep their status. The rest stay unknown.
        withTimeoutOrNull(VERIFY_BUDGET_MS) {
            coroutineScope {
                toCheck.forEach { scored ->
                    launch(Dispatchers.IO) {
                        gate.withPermit {
                            statuses[scored.item.url] = PageScraper.check(scored.item.url)
                        }
                    }
                }
            }
        }

        val playable = toCheck
            .filter { statuses[it.item.url] == PageCheck.PLAYABLE }
            .map { it.item }
        val rejected = toCheck.count { scored ->
            val status = statuses[scored.item.url]
            status != null && status != PageCheck.PLAYABLE && status != PageCheck.NETWORK_ERROR
        }
        val uncertain = (
            toCheck
                .filter { scored ->
                    val status = statuses[scored.item.url]
                    status == null || status == PageCheck.NETWORK_ERROR
                }
                .map { it.item } + notChecked
            ).take(UNCERTAIN_LIMIT)

        return WebScan(playable = playable, uncertain = uncertain, rejected = rejected)
    }

    private suspend fun rankedCandidates(query: String, queries: List<String>): List<Scored> =
        ranked(query, collect(queries)).take(MAX_RESULTS)

    /** Runs the queries with a small limit on how many run at once. Each query asks DuckDuckGo and Bing. */
    private suspend fun collect(queries: List<String>): List<SearchHit> = coroutineScope {
        val gate = Semaphore(QUERY_PARALLEL)
        queries.map { query ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    coroutineScope {
                        val duck = async(Dispatchers.IO) { searchDuckDuckGo(query) }
                        val bing = async(Dispatchers.IO) { searchBing(query) }
                        duck.await() + bing.await()
                    }
                }
            }
        }.awaitAll().flatten()
    }

    /** Keeps relevant, safe, unique pages, best match first. */
    private fun ranked(query: String, hits: List<SearchHit>): List<Scored> {
        val required = requiredMatches(query)
        if (required == 0) return emptyList()
        val seen = HashSet<String>()
        return hits.asSequence()
            .filter { !isAdult(it.item, it.context) }
            .map { Scored(it.item, matchCount(query, it.item, it.context)) }
            .filter { it.score >= required }
            .filter { seen.add(it.item.url.substringBefore('#').trimEnd('/')) }
            .sortedByDescending { it.score }
            .toList()
    }

    private fun isAdult(item: VideoItem, context: String): Boolean {
        val host = runCatching { Uri.parse(item.url).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        if (ADULT_HOST_MARKERS.any { host.contains(it) }) return true
        val text = item.title + " " + item.channel + " " + item.url + " " + context
        return ADULT_TEXT_MARKERS.any { text.contains(it, ignoreCase = true) }
    }

    /**
     * Words of the query found in the title, address or snippet. Two words are required for a title
     * of two or more words. A one-word title must match that word. Host names are not used as a match.
     */
    private fun requiredMatches(query: String): Int {
        val wanted = tokens(query)
        return if (wanted.isEmpty()) 0 else minOf(2, wanted.size)
    }

    private fun matchCount(query: String, item: VideoItem, context: String): Int {
        val wanted = tokens(query)
        if (wanted.isEmpty()) return 0
        val uri = Uri.parse(item.url)
        val pageText = item.title + " " + uri.path.orEmpty() + " " +
            uri.query.orEmpty().replace('+', ' ') + " " + context
        val available = tokens(pageText).toHashSet()
        return wanted.count { it in available }
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
            .maxBodySize(1024 * 1024)
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
            .maxBodySize(1024 * 1024)
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
