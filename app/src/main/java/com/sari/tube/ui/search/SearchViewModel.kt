package com.sari.tube.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sari.tube.engine.SearchException
import com.sari.tube.engine.VideoItem
import com.sari.tube.engine.WebSearch
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
    WEB("الويب"),
    TIKTOK("TikTok"),
    FACEBOOK("Facebook"),
    LOCAL("محلي");

    val searchable: Boolean get() = this != LOCAL
}

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Ready(val items: List<VideoItem>, val hasMore: Boolean = false) : SearchUiState
    data class Failed(val message: String) : SearchUiState
}

class SearchViewModel : ViewModel() {
    /** True while the next batch of YouTube results is loading. */
    var loadingMore by mutableStateOf(false)
        private set

    private var limit = BATCH

    var query by mutableStateOf("")
        private set

    var tab by mutableStateOf(SearchTab.ALL)
        private set

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
        debounceJob?.cancel()

        if (value.isBlank() || tab == SearchTab.LOCAL) {
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
        debounceJob?.cancel()
        searchJob?.cancel()

        if (selected == SearchTab.LOCAL) {
            state = SearchUiState.Idle
        } else if (query.isNotBlank()) {
            submit()
        }
    }

    fun startSearch(q: String) {
        query = q
        limit = BATCH
        tab = SearchTab.ALL
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
            SearchTab.LOCAL -> emptyList()
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

    companion object {
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
