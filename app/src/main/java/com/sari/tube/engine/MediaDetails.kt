package com.sari.tube.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One season of a series. Fields the source does not give are null and are not shown. */
data class SeasonInfo(
    val number: Int,
    val name: String,
    val episodeCount: Int?,
    val year: String?
)

data class EpisodeInfo(
    val number: Int,
    val name: String,
    val airDate: String?,
    val runtimeMin: Int?,
    val overview: String
)

data class MediaDetails(
    val runtimeMin: Int?,
    val genres: List<String>,
    val collectionName: String?,
    val seasons: List<SeasonInfo>,
    val seasonCount: Int?,
    val episodeCount: Int?
)

/** Loads details from the same services as the search. Anything the service lacks stays empty. */
object MediaDetailsLoader {
    private const val TMDB_BASE = "https://api.themoviedb.org/3/"
    private const val TVMAZE_BASE = "https://api.tvmaze.com/"

    suspend fun details(item: UnifiedMediaItem): MediaDetails = withContext(Dispatchers.IO) {
        when (item.service) {
            MetaService.TMDB -> tmdbDetails(item)
            MetaService.TVMAZE -> tvmazeDetails(item)
            MetaService.ANILIST -> MediaDetails(null, emptyList(), null, emptyList(), null, null)
        }
    }

    /** Episodes of one season. Empty when the service has no episode data for it. */
    suspend fun episodes(item: UnifiedMediaItem, season: Int): List<EpisodeInfo> = withContext(Dispatchers.IO) {
        when (item.service) {
            MetaService.TMDB -> {
                val key = tmdbKey()
                val arr = JSONObject(MetadataHttp.get("${TMDB_BASE}tv/${item.serviceId}/season/$season?api_key=$key&language=ar"))
                    .optJSONArray("episodes") ?: JSONArray()
                objects(arr).map { e ->
                    EpisodeInfo(
                        number = e.optInt("episode_number", 0),
                        name = e.optString("name").ifBlank { "الحلقة ${e.optInt("episode_number", 0)}" },
                        airDate = e.optString("air_date").takeIf { it.isNotBlank() && it != "null" },
                        runtimeMin = e.optInt("runtime", 0).takeIf { it > 0 },
                        overview = e.optString("overview")
                    )
                }
            }
            MetaService.TVMAZE -> tvmazeEpisodes(item.serviceId)
                .filter { it.optInt("season", -1) == season }
                .map { e ->
                    EpisodeInfo(
                        number = e.optInt("number", 0),
                        name = e.optString("name").ifBlank { "الحلقة ${e.optInt("number", 0)}" },
                        airDate = e.optString("airdate").takeIf { it.isNotBlank() && it != "null" },
                        runtimeMin = e.optInt("runtime", 0).takeIf { it > 0 },
                        overview = stripHtml(e.optString("summary"))
                    )
                }
            MetaService.ANILIST -> emptyList()
        }
    }

    private fun tmdbKey(): String =
        MetadataKeys.tmdb() ?: throw MetadataException("أضف مفتاح TMDB في الإعدادات لعرض التفاصيل")

    private fun tmdbDetails(item: UnifiedMediaItem): MediaDetails {
        val key = tmdbKey()
        return if (item.kind == MetaKind.MOVIE) {
            val o = JSONObject(MetadataHttp.get("${TMDB_BASE}movie/${item.serviceId}?api_key=$key&language=ar"))
            MediaDetails(
                runtimeMin = o.optInt("runtime", 0).takeIf { it > 0 },
                genres = genresOf(o),
                collectionName = o.optJSONObject("belongs_to_collection")?.optString("name")?.takeIf { it.isNotBlank() },
                seasons = emptyList(),
                seasonCount = null,
                episodeCount = null
            )
        } else {
            val o = JSONObject(MetadataHttp.get("${TMDB_BASE}tv/${item.serviceId}?api_key=$key&language=ar"))
            val seasons = objects(o.optJSONArray("seasons") ?: JSONArray()).mapNotNull { s ->
                val n = s.optInt("season_number", -1)
                if (n <= 0) return@mapNotNull null
                SeasonInfo(
                    number = n,
                    name = s.optString("name").ifBlank { "الموسم $n" },
                    episodeCount = s.optInt("episode_count", 0).takeIf { it > 0 },
                    year = s.optString("air_date").take(4).takeIf { it.length == 4 }
                )
            }
            MediaDetails(
                runtimeMin = o.optIntArrayFirst("episode_run_time"),
                genres = genresOf(o),
                collectionName = null,
                seasons = seasons,
                seasonCount = o.optInt("number_of_seasons", 0).takeIf { it > 0 },
                episodeCount = o.optInt("number_of_episodes", 0).takeIf { it > 0 }
            )
        }
    }

    private fun tvmazeDetails(item: UnifiedMediaItem): MediaDetails {
        val episodes = tvmazeEpisodes(item.serviceId)
        val bySeason = episodes.groupBy { it.optInt("season", 0) }.filterKeys { it > 0 }
        val seasons = bySeason.toSortedMap().map { (n, list) ->
            SeasonInfo(
                number = n,
                name = "الموسم $n",
                episodeCount = list.size,
                year = list.firstNotNullOfOrNull { it.optString("airdate").take(4).takeIf { y -> y.length == 4 } }
            )
        }
        return MediaDetails(
            runtimeMin = null,
            genres = emptyList(),
            collectionName = null,
            seasons = seasons,
            seasonCount = seasons.size.takeIf { it > 0 },
            episodeCount = episodes.size.takeIf { it > 0 }
        )
    }

    private fun tvmazeEpisodes(showId: String): List<JSONObject> =
        objects(JSONArray(MetadataHttp.get("${TVMAZE_BASE}shows/$showId/episodes")))

    private fun genresOf(o: JSONObject): List<String> =
        objects(o.optJSONArray("genres") ?: JSONArray())
            .map { it.optString("name") }
            .filter { it.isNotBlank() }

    private fun JSONObject.optIntArrayFirst(key: String): Int? {
        val arr = optJSONArray(key) ?: return null
        return arr.optInt(0, 0).takeIf { it > 0 }
    }

    private fun objects(arr: JSONArray): List<JSONObject> =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").trim()
}
