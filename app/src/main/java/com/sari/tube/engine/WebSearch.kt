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

/** Multi-engine discovery. Results are candidates; PlaybackResolver verifies media on open. */
object WebSearch {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_RESULTS = 30

    suspend fun search(query: String, site: String? = null): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) throw SearchException("اكتب اسم الفيلم أو المسلسل أولاً.")

            val scoped = when (site) {
                "tiktok" -> "site:tiktok.com $clean"
                "facebook" -> "site:facebook.com $clean"
                else -> clean
            }
            val queries = listOf(scoped, "\"$clean\" watch online").distinct()

            val batches = coroutineScope {
                queries.flatMap { q ->
                    listOf(
                        async(Dispatchers.IO) { searchDuckDuckGo(q) },
                        async(Dispatchers.IO) { searchBing(q) }
                    )
                }.awaitAll()
            }

            val seen = HashSet<String>()
            val merged = ArrayList<VideoItem>()
            for (batch in batches) {
                for (item in batch) {
                    val key = item.url.substringBefore('#').trimEnd('/')
                    if (seen.add(key)) merged += item
                    if (merged.size >= MAX_RESULTS) break
                }
                if (merged.size >= MAX_RESULTS) break
            }

            if (merged.isEmpty()) {
                throw SearchException(
                    "لم تُرجع محركات البحث نتائج. تحقق من الاتصال أو جرّب اسم الفيلم بالإنجليزية."
                )
            }
            merged
        }

    private fun searchDuckDuckGo(query: String): List<VideoItem> = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect("https://html.duckduckgo.com/html/?q=$encoded")
            .userAgent(USER_AGENT)
            .referrer("https://duckduckgo.com/")
            .timeout(9_000)
            .maxBodySize(2 * 1024 * 1024)
            .get()

        val out = ArrayList<VideoItem>()
        doc.select("a.result__a, .result__title a").forEach { a ->
            val url = unwrap(a.attr("href").trim())
            val title = a.text().trim()
            val host = Uri.parse(url).host.orEmpty()
            if (isHttp(url) && host.isNotBlank() &&
                !host.contains("duckduckgo.com") && title.isNotBlank()
            ) {
                out += item(title, host, url)
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun searchBing(query: String): List<VideoItem> = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = Jsoup.connect("https://www.bing.com/search?format=rss&q=$encoded")
            .userAgent(USER_AGENT)
            .referrer("https://www.bing.com/")
            .timeout(9_000)
            .maxBodySize(2 * 1024 * 1024)
            .ignoreContentType(true)
            .execute()
            .body()

        val out = ArrayList<VideoItem>()
        if (body.contains("<rss", true) || body.contains("<item", true)) {
            val xml = Jsoup.parse(body, "", Parser.xmlParser())
            xml.select("item").forEach { node ->
                val title = node.selectFirst("title")?.text().orEmpty().trim()
                val url = node.selectFirst("link")?.text().orEmpty().trim()
                val host = Uri.parse(url).host.orEmpty()
                if (title.isNotBlank() && isHttp(url) && host.isNotBlank()) {
                    out += item(title, host, url)
                }
            }
        }

        if (out.isEmpty()) {
            val html = Jsoup.parse(body)
            html.select("li.b_algo h2 a, h2 a").forEach { a ->
                val url = unwrap(a.attr("href").trim())
                val title = a.text().trim()
                val host = Uri.parse(url).host.orEmpty()
                if (title.isNotBlank() && isHttp(url) &&
                    host.isNotBlank() && !host.contains("bing.com")
                ) {
                    out += item(title, host, url)
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
