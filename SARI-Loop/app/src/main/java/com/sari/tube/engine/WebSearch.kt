package com.sari.tube.engine

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

object WebSearch {
    suspend fun search(query: String, site: String? = null): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val scopedQuery = when (site) {
                "tiktok" -> "site:tiktok.com $query"
                "facebook" -> "site:facebook.com $query"
                else -> query
            }

            try {
                val encoded = URLEncoder.encode(scopedQuery, "UTF-8")
                val document = Jsoup.connect(
                    "https://html.duckduckgo.com/html/?q=$encoded"
                )
                    .userAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    .referrer("https://duckduckgo.com/")
                    .timeout(9000)
                    .maxBodySize(2 * 1024 * 1024)
                    .get()

                val seen = HashSet<String>()
                val results = ArrayList<VideoItem>()

                document.select("a.result__a, .result__title a").forEach { anchor ->
                    if (results.size >= 10) return@forEach

                    val raw = anchor.attr("href").trim()
                    if (raw.isBlank()) return@forEach

                    val parsed = Uri.parse(
                        if (raw.startsWith("//")) "https:$raw" else raw
                    )
                    val target = parsed.getQueryParameter("uddg") ?: raw
                    val url = target.trim()

                    if (!url.startsWith("https://") && !url.startsWith("http://")) {
                        return@forEach
                    }

                    val host = Uri.parse(url).host.orEmpty()
                    if (host.isBlank() || host.contains("duckduckgo.com")) {
                        return@forEach
                    }

                    val title = anchor.text().trim()
                    if (title.isBlank() || !seen.add(url)) return@forEach

                    val snippet = anchor.parents().firstOrNull {
                        it.hasClass("result")
                    }?.selectFirst(".result__snippet")?.text().orEmpty()

                    results += VideoItem(
                        id = "web_${url.hashCode()}",
                        title = title,
                        channel = host.removePrefix("www."),
                        durationSec = 0L,
                        thumbnail = null,
                        url = url,
                        views = null
                    )
                }

                if (results.isEmpty()) {
                    throw SearchException("لم يعثر بحث الويب على نتائج. حاول بعبارة أخرى.")
                }
                results
            } catch (e: SearchException) {
                throw e
            } catch (e: Exception) {
                throw SearchException(
                    "تعذّر الاتصال بمحرك بحث الويب: ${e.message?.lineSequence()?.firstOrNull().orEmpty().take(100)}"
                )
            }
        }
}
