package com.sari.tube.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sari.tube.engine.SearchException
import com.sari.tube.engine.VideoItem
import com.sari.tube.engine.YtDlpSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SearchTab(val label: String) {
    ALL("الكل"),
    SONGS("أغاني"),
    VIDEOS("فيديو"),
    GOOGLE("Google"),
    TIKTOK("TikTok"),
    FACEBOOK("Facebook");

    /** yt-dlp يبحث في يوتيوب فقط. التبويبات الأخرى لا تملك بحثاً مباشراً هنا. */
    val searchable: Boolean get() = this == ALL || this == SONGS || this == VIDEOS
}

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Ready(val items: List<VideoItem>) : SearchUiState
    data class Failed(val message: String) : SearchUiState
}

class SearchViewModel : ViewModel() {
    var query by mutableStateOf("")
        private set
    var tab by mutableStateOf(SearchTab.ALL)
        private set
    var state by mutableStateOf<SearchUiState>(SearchUiState.Idle)
        private set

    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    /** ذاكرة صغيرة: تكرار نفس الاستعلام يعرض النتائج فوراً. */
    private val cache = object : LinkedHashMap<String, List<VideoItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<VideoItem>>?): Boolean =
            size > 20
    }

    fun onQueryChange(value: String) {
        query = value
        debounceJob?.cancel()
        if (value.isBlank()) {
            searchJob?.cancel()
            state = SearchUiState.Idle
            return
        }
        // ينتظر 700 ms بعد آخر حرف حتى لا يبدأ بحثاً مع كل ضغطة.
        debounceJob = viewModelScope.launch {
            delay(700)
            submit()
        }
    }

    fun onTabSelected(selected: SearchTab) {
        tab = selected
    }

    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return

        // الاستعلام الجديد يلغي البحث السابق فوراً (يوقف عملية yt-dlp أيضاً).
        searchJob?.cancel()
        cache[q]?.let {
            state = SearchUiState.Ready(it)
            return
        }

        state = SearchUiState.Loading
        searchJob = viewModelScope.launch {
            try {
                val items = YtDlpSearch.search(q)
                cache[q] = items
                state = SearchUiState.Ready(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchException) {
                state = SearchUiState.Failed(e.message ?: "فشل البحث")
            } catch (e: Exception) {
                state = SearchUiState.Failed("خطأ غير متوقع: ${e.message}")
            }
        }
    }

    fun retry() = submit()

    override fun onCleared() {
        searchJob?.cancel()
        debounceJob?.cancel()
        super.onCleared()
    }

    companion object {
        private val SONG_HINTS = listOf(
            "official audio", "audio", "lyrics", "lyric", "karaoke", "song", "music",
            "كلمات", "أغنية", "اغنية", "أغاني", "اغاني", "كاريوكي", "موسيقى"
        )

        /**
         * تخمين بسيط: نتائج عناوينها أو قنواتها تشبه الأغاني.
         * يوتيوب لا يعطي علامة موسيقى موثوقة في نتائج البحث، لذلك هذا التصنيف تقريبي.
         */
        fun isSong(item: VideoItem): Boolean {
            if (item.channel.endsWith("- Topic")) return true
            val text = (item.title + " " + item.channel).lowercase()
            return SONG_HINTS.any { text.contains(it) }
        }
    }
}
