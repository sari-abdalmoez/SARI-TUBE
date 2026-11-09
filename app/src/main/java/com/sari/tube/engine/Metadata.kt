package com.sari.tube.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Metadata services. They describe titles only; they never provide video files. */
enum class MetaService { TMDB, ANILIST, TVMAZE }

enum class MetaKind { MOVIE, SERIES, ANIME }

/** One result shape for every metadata service. serviceId keeps the id from the original service. */
data class UnifiedMediaItem(
    val service: MetaService,
    val serviceId: String,
    val kind: MetaKind,
    val title: String,
    val altTitles: List<String>,
    val year: Int?,
    val overview: String,
    val posterUrl: String?,
    val rating: Double?
)

data class MetaSearchReport(
    val items: List<UnifiedMediaItem>,
    /** One short Arabic reason per service that failed. A failure never hides the other services. */
    val failures: Map<MetaService, String>
)

class MetadataException(message: String) : Exception(message)

/** API keys live in this app's private preferences, entered by the user. Nothing is compiled into the app. */
object MetadataKeys {
    private const val PREFS = "sari_tube_api_keys"
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun tmdb(): String? = appContext
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ?.getString("tmdb", null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun setTmdb(context: Context, key: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("tmdb", key.trim()).apply()
    }
}

object MetadataSearch {
    private const val TIMEOUT_MS = 12_000L

    /** Searches all three services in parallel. Each service gets its own timeout and failure. */
    suspend fun search(query: String): MetaSearchReport = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) return@coroutineScope MetaSearchReport(emptyList(), emptyMap())

        // Each call returns a Result instead of throwing, so one failing service cannot cancel the others.
        val calls = listOf(
            MetaService.TMDB to async { runCatching { withTimeoutOrNull(TIMEOUT_MS) { tmdb(q) } } },
            MetaService.ANILIST to async { runCatching { withTimeoutOrNull(TIMEOUT_MS) { anilist(q) } } },
            MetaService.TVMAZE to async { runCatching { withTimeoutOrNull(TIMEOUT_MS) { tvmaze(q) } } }
        )
        val items = ArrayList<UnifiedMediaItem>()
        val failures = HashMap<MetaService, String>()
        for ((service, job) in calls) {
            job.await().fold(
                onSuccess = { result ->
                    if (result == null) failures[service] = "انتهت مهلة الاتصال"
                    else items.addAll(result)
                },
                onFailure = { e ->
                    failures[service] = if (e is MetadataException) e.message ?: "خطأ من الخدمة"
                    else "تعذّر الاتصال بالخدمة"
                }
            )
        }
        MetaSearchReport(items, failures)
    }

    private suspend fun tmdb(q: String): List<UnifiedMediaItem> = withContext(Dispatchers.IO) {
        val key = MetadataKeys.tmdb() ?: throw MetadataException("أضف مفتاح TMDB في الإعدادات")
        val url = "https://api.themoviedb.org/3/search/multi?api_key=$key&include_adult=false&language=ar&query=${enc(q)}"
        val results = JSONObject(MetadataHttp.get(url)).optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val type = o.optString("media_type")
            if (type != "movie" && type != "tv") return@mapNotNull null
            val isMovie = type == "movie"
            val title = o.optString(if (isMovie) "title" else "name").ifBlank { return@mapNotNull null }
            val original = o.optString(if (isMovie) "original_title" else "original_name")
            val date = o.optString(if (isMovie) "release_date" else "first_air_date")
            val poster = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                ?.let { "https://image.tmdb.org/t/p/w342$it" }
            UnifiedMediaItem(
                service = MetaService.TMDB,
                serviceId = o.optInt("id").toString(),
                kind = if (isMovie) MetaKind.MOVIE else MetaKind.SERIES,
                title = title,
                altTitles = listOfNotNull(original.takeIf { it.isNotBlank() && it != title }),
                year = date.take(4).toIntOrNull(),
                overview = o.optString("overview"),
                posterUrl = poster,
                rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
            )
        }
    }

    private suspend fun anilist(q: String): List<UnifiedMediaItem> = withContext(Dispatchers.IO) {
        // Public search needs no key. Do not put AniList client secrets in the app.
        val gql = """query (${'$'}search: String) { Page(perPage: 10) { media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
            id title { romaji english native } startDate { year } description(asHtml: false) coverImage { large } averageScore } } }"""
        val body = JSONObject().put("query", gql).put("variables", JSONObject().put("search", q)).toString()
        val root = JSONObject(MetadataHttp.post("https://graphql.anilist.co", body))
        val media = root.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            ?: return@withContext emptyList()
        (0 until media.length()).mapNotNull { i ->
            val o = media.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optJSONObject("title")
            val english = t?.optString("english").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val romaji = t?.optString("romaji").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val native = t?.optString("native").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val title = english ?: romaji ?: native ?: return@mapNotNull null
            UnifiedMediaItem(
                service = MetaService.ANILIST,
                serviceId = o.optInt("id").toString(),
                kind = MetaKind.ANIME,
                title = title,
                altTitles = listOfNotNull(romaji, native).filter { it != title }.distinct(),
                year = o.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 },
                overview = stripHtml(o.optString("description")),
                posterUrl = o.optJSONObject("coverImage")?.optString("large")?.takeIf { it.isNotBlank() && it != "null" },
                rating = o.optDouble("averageScore", 0.0).takeIf { it > 0.0 }?.div(10.0)
            )
        }
    }

    private suspend fun tvmaze(q: String): List<UnifiedMediaItem> = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray(MetadataHttp.get("https://api.tvmaze.com/search/shows?q=${enc(q)}"))
        (0 until arr.length()).mapNotNull { i ->
            val show = arr.optJSONObject(i)?.optJSONObject("show") ?: return@mapNotNull null
            val title = show.optString("name").ifBlank { return@mapNotNull null }
            UnifiedMediaItem(
                service = MetaService.TVMAZE,
                serviceId = show.optInt("id").toString(),
                kind = MetaKind.SERIES,
                title = title,
                altTitles = emptyList(),
                year = show.optString("premiered").take(4).toIntOrNull(),
                overview = stripHtml(show.optString("summary")),
                posterUrl = show.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() && it != "null" },
                rating = show.optJSONObject("rating")?.optDouble("average", 0.0)?.takeIf { it > 0.0 }
            )
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()
}

/** Plain HTTP helper with timeouts. Errors become short messages, and the response body is never shown. */
object MetadataHttp {
    private const val USER_AGENT = "SARI-Loop/0.8 metadata client"

    fun get(url: String): String = request(url, "GET", null)

    fun post(url: String, body: String): String = request(url, "POST", body)

    private fun request(url: String, method: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw MetadataException(describe(code))
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(code: Int): String = when (code) {
        401, 403 -> "مفتاح الخدمة غير صالح أو مرفوض"
        404 -> "المصدر غير موجود"
        429 -> "طلبات كثيرة، جرّب بعد قليل"
        else -> "خطأ من الخدمة ($code)"
    }
}
