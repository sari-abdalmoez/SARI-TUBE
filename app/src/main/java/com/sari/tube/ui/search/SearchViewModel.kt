package com.sari.tube.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sari.tube.engine.SearchException
import com.sari.tube.engine.VideoItem
import com.sari.tube.engine.WebSearch
import com.sari.tube.engine.MetaKind
import com.sari.tube.engine.MetaService
import com.sari.tube.engine.UnifiedMediaItem
import androidx.lifecycle.SavedStateHandle
import org.json.JSONArray
import org.json.JSONObject
import com.sari.tube.engine.YtDlpSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SearchTab(val label: String) {
    ALL("الكل"),
    SONGS("أغاني"),
    VIDEOS("فيديو"),
    MOVIES("أفلام ومسلسلات"),
    WEB("الويب"),
    TIKTOK("TikTok"),
    FACEBOOK("Facebook"),
    LOCAL("محلي");

    /** Tabs that search YouTube or the web. Local files and films/series use their own panels. */
    val searchable: Boolean get() = this != LOCAL && this != MOVIES
}

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Ready(val items: List<VideoItem>, val hasMore: Boolean = false) : SearchUiState
    data class Failed(val message: String) : SearchUiState
}

/**
 * Search state. It is saved in [saved] so it survives the system recreating the activity
 * (common when the app is in the background). It is cleared only when the app is closed.
 */
class SearchViewModel(private val saved: SavedStateHandle) : ViewModel() {
    /** True while the next batch of YouTube results is loading. */
    var loadingMore by mutableStateOf(false)
        private set

    private var limit = BATCH

    var query by mutableStateOf(saved.get<String>(KEY_QUERY).orEmpty())
        private set

    var tab by mutableStateOf(
        saved.get<String>(KEY_TAB)?.let { name -> runCatching { SearchTab.valueOf(name) }.getOrNull() }
            ?: SearchTab.ALL
    )
        private set

    /** Film or series whose details page is open. */
    var openMedia by mutableStateOf(SavedItems.media(saved.get<String>(KEY_MEDIA)))
        private set

    /** Query whose web results are shown while the user picks a page to watch. */
    var watchQuery by mutableStateOf(saved.get<String>(KEY_WATCH))
        private set

    /** Result the player is showing. */
    var playItem by mutableStateOf(SavedItems.video(saved.get<String>(KEY_PLAY)))
        private set

    fun openMediaItem(item: UnifiedMediaItem?) {
        openMedia = item
        saved[KEY_MEDIA] = item?.let { SavedItems.mediaJson(it) }
    }

    fun setWatch(value: String?) {
        watchQuery = value
        saved[KEY_WATCH] = value
    }

    fun playVideo(item: VideoItem?) {
        playItem = item
        saved[KEY_PLAY] = item?.let { SavedItems.videoJson(it) }
    }

    var state by mutableStateOf<SearchUiState>(SearchUiState.Idle)
        private set

    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    private var requestSeq = 0

    private val cache = object : LinkedHashMap<String, List<VideoItem>>(24, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<VideoItem>>?
        ): Boolean = size > 24
    }

    fun onQueryChange(value: String) {
        query = value
        saved[KEY_QUERY] = value
        debounceJob?.cancel()

        if (value.isBlank() || !tab.searchable) {
            searchJob?.cancel()
            state = SearchUiState.Idle
            return
        }

        debounceJob = viewModelScope.launch {
            delay(300)
            submit()
        }
    }

    fun onTabSelected(selected: SearchTab) {
        limit = BATCH
        tab = selected
        saved[KEY_TAB] = selected.name
        debounceJob?.cancel()
        searchJob?.cancel()

        if (!selected.searchable) {
            state = SearchUiState.Idle
        } else if (query.isNotBlank()) {
            submit()
        }
    }

    fun startSearch(q: String) {
        query = q
        saved[KEY_QUERY] = q
        limit = BATCH
        tab = SearchTab.ALL
        saved[KEY_TAB] = SearchTab.ALL.name
        debounceJob?.cancel()
        submit()
    }

    fun submit() {
        val q = query.trim()
        val selectedTab = tab
        if (q.isEmpty() || !selectedTab.searchable) return

        searchJob?.cancel()
        val seq = ++requestSeq
        val cacheKey = "${selectedTab.name}:${q.lowercase()}"

        cache[cacheKey]?.let {
            state = SearchUiState.Ready(it, hasMoreFor(it))
            return
        }

        state = SearchUiState.Loading
        searchJob = viewModelScope.launch {
            try {
                val items = loadResults(q, selectedTab, limit)
                cache[cacheKey] = items
                if (seq == requestSeq) state = SearchUiState.Ready(items, hasMoreFor(items))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchException) {
                if (seq == requestSeq) {
                    state = SearchUiState.Failed(e.message ?: "فشل البحث")
                }
            } catch (e: Exception) {
                if (seq == requestSeq) {
                    state = SearchUiState.Failed("تعذّر إكمال البحث: ${e.message}")
                }
            }
        }
    }

    /** Loads the next batch of YouTube results. The list keeps what is already shown if this fails. */
    fun loadMore() {
        val ready = state as? SearchUiState.Ready ?: return
        if (!ready.hasMore || loadingMore) return
        val q = query.trim()
        val selectedTab = tab
        val cacheKey = "${selectedTab.name}:${q.lowercase()}"
        val seq = requestSeq
        loadingMore = true
        limit += BATCH
        viewModelScope.launch {
            try {
                val items = loadResults(q, selectedTab, limit)
                cache[cacheKey] = items
                if (seq == requestSeq) state = SearchUiState.Ready(items, hasMoreFor(items))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                limit -= BATCH
            } finally {
                loadingMore = false
            }
        }
    }

    private fun hasMoreFor(items: List<VideoItem>): Boolean = items.count { isYoutube(it) } >= limit

    private fun isYoutube(item: VideoItem): Boolean =
        item.url.contains("youtube.com") || item.url.contains("youtu.be")

    private suspend fun loadResults(
        q: String,
        source: SearchTab,
        count: Int
    ): List<VideoItem> {
        return when (source) {
            SearchTab.ALL -> coroutineScope {
                val youtube = async {
                    safeSearch { YtDlpSearch.search(q, count) }
                }
                val web = async {
                    safeSearch { WebSearch.search(q) }
                }

                val ytItems = youtube.await().getOrElse { emptyList() }
                val webItems = web.await().getOrElse { emptyList() }

                val merged = (ytItems + webItems)
                    .distinctBy { it.url }

                if (merged.isEmpty()) {
                    throw SearchException("لم تظهر نتائج. تحقق من الاتصال أو جرّب عبارة أخرى.")
                }
                merged
            }

            SearchTab.SONGS, SearchTab.VIDEOS -> YtDlpSearch.search(q, count)
            SearchTab.WEB -> WebSearch.search(q)
            SearchTab.TIKTOK -> WebSearch.search(q, "tiktok")
            SearchTab.FACEBOOK -> WebSearch.search(q, "facebook")
            SearchTab.LOCAL, SearchTab.MOVIES -> emptyList()
        }
    }

    private suspend fun <T> safeSearch(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun retry() = submit()

    override fun onCleared() {
        searchJob?.cancel()
        debounceJob?.cancel()
        super.onCleared()
    }

    init {
        // Restored after a recreation: run the search again so the results come back.
        if (query.isNotBlank() && tab.searchable) submit()
    }

    companion object {
        private const val KEY_QUERY = "query"
        private const val KEY_TAB = "tab"
        private const val KEY_MEDIA = "openMedia"
        private const val KEY_WATCH = "watchQuery"
        private const val KEY_PLAY = "playItem"

        /** Results per batch. Another batch loads when the user reaches the end of the list. */
        const val BATCH = 6
        private val SONG_HINTS = listOf(
            "official audio", "audio", "lyrics", "lyric", "karaoke", "song", "music",
            "كلمات", "أغنية", "اغنية", "أغاني", "اغاني", "كاريوكي", "موسيقى"
        )

        fun isSong(item: VideoItem): Boolean {
            if (item.channel.endsWith("- Topic")) return true
            val text = (item.title + " " + item.channel).lowercase()
            return SONG_HINTS.any { text.contains(it) }
        }
    }
}

/** JSON conversion for values kept in [SavedStateHandle]. Invalid data is ignored instead of crashing. */
internal object SavedItems {
    fun mediaJson(i: UnifiedMediaItem): String = JSONObject()
        .put("service", i.service.name)
        .put("serviceId", i.serviceId)
        .put("kind", i.kind.name)
        .put("title", i.title)
        .put("altTitles", JSONArray(i.altTitles))
        .put("year", i.year ?: JSONObject.NULL)
        .put("overview", i.overview)
        .put("posterUrl", i.posterUrl ?: JSONObject.NULL)
        .put("rating", i.rating ?: JSONObject.NULL)
        .toString()

    fun media(json: String?): UnifiedMediaItem? {
        if (json == null) return null
        return runCatching {
            val o = JSONObject(json)
            val alt = o.optJSONArray("altTitles") ?: JSONArray()
            UnifiedMediaItem(
                service = MetaService.valueOf(o.getString("service")),
                serviceId = o.getString("serviceId"),
                kind = MetaKind.valueOf(o.getString("kind")),
                title = o.getString("title"),
                altTitles = (0 until alt.length()).map { alt.getString(it) },
                year = if (o.isNull("year")) null else o.getInt("year"),
                overview = o.optString("overview"),
                posterUrl = if (o.isNull("posterUrl")) null else o.getString("posterUrl"),
                rating = if (o.isNull("rating")) null else o.getDouble("rating")
            )
        }.getOrNull()
    }

    fun videoJson(v: VideoItem): String = JSONObject()
        .put("id", v.id)
        .put("title", v.title)
        .put("channel", v.channel)
        .put("durationSec", v.durationSec)
        .put("thumbnail", v.thumbnail ?: JSONObject.NULL)
        .put("url", v.url)
        .put("views", v.views ?: JSONObject.NULL)
        .toString()

    fun video(json: String?): VideoItem? {
        if (json == null) return null
        return runCatching {
            val o = JSONObject(json)
            VideoItem(
                id = o.getString("id"),
                title = o.getString("title"),
                channel = o.optString("channel"),
                durationSec = o.optLong("durationSec", 0L),
                thumbnail = if (o.isNull("thumbnail")) null else o.getString("thumbnail"),
                url = o.getString("url"),
                views = if (o.isNull("views")) null else o.getLong("views")
            )
        }.getOrNull()
    }
}
