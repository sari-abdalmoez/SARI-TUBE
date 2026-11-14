package com.sari.tube.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DlState { QUEUED, RUNNING, FAILED }

/**
 * Live state of one download. The notification and the app read the same values.
 * percent = -1 when unknown. totalBytes, speedBps and etaSec = -1 when the engine does not report them.
 */
data class ActiveDownload(
    val id: Int,
    val title: String,
    val state: DlState,
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val etaSec: Long,
    val message: String
)

object ActiveDownloads {
    private val _items = MutableStateFlow<Map<Int, ActiveDownload>>(emptyMap())
    val items: StateFlow<Map<Int, ActiveDownload>> = _items.asStateFlow()

    fun put(item: ActiveDownload) {
        _items.update { it + (item.id to item) }
    }

    fun modify(id: Int, block: (ActiveDownload) -> ActiveDownload) {
        _items.update { m -> m[id]?.let { m + (id to block(it)) } ?: m }
    }

    fun remove(id: Int) {
        _items.update { it - id }
    }
}
