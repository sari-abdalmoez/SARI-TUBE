package com.sari.tube.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DlState {
    QUEUED,
    RUNNING,
    FAILED
}

data class ActiveDownload(
    val id: Int,
    val title: String,
    val state: DlState,
    val progress: Int = -1,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val message: String = "",
    val thumbnail: String? = null
)

/** What was asked for, kept so a failed download can be retried. */
data class DownloadRequest(val input: String, val selector: String, val audioOnly: Boolean)

private val requests = java.util.concurrent.ConcurrentHashMap<Int, DownloadRequest>()

fun rememberRequest(id: Int, request: DownloadRequest) {
    requests[id] = request
}

fun requestOf(id: Int): DownloadRequest? = requests[id]

/**
 * In-memory state shared between DownloadService and the Compose UI.
 * StateFlow keeps the screen updated without polling the service.
 */
object ActiveDownloads {
    private val lock = Any()
    private val mutableItems = MutableStateFlow<Map<Int, ActiveDownload>>(emptyMap())

    val items: StateFlow<Map<Int, ActiveDownload>> = mutableItems.asStateFlow()

    fun setThumbnail(id: Int, url: String) {
        mutate { map -> map[id]?.let { map[id] = it.copy(thumbnail = url) } }
    }

    fun enqueue(id: Int, title: String) {
        mutate { map ->
            map[id] = ActiveDownload(
                id = id,
                title = title,
                state = DlState.QUEUED,
                message = "في قائمة الانتظار"
            )
        }
    }

    fun running(id: Int) {
        mutate { map ->
            val old = map[id]
            if (old != null) {
                map[id] = old.copy(
                    state = DlState.RUNNING,
                    message = "جارٍ بدء التحميل"
                )
            }
        }
    }

    fun updateProgress(
        id: Int,
        title: String? = null,
        progress: Int? = null,
        downloadedBytes: Long? = null,
        totalBytes: Long? = null,
        speedBytesPerSec: Long? = null,
        etaSeconds: Long? = null,
        message: String? = null
    ) {
        mutate { map ->
            val old = map[id]
            if (old != null) {
                map[id] = old.copy(
                    title = title ?: old.title,
                    state = DlState.RUNNING,
                    progress = progress ?: old.progress,
                    downloadedBytes = downloadedBytes ?: old.downloadedBytes,
                    totalBytes = totalBytes ?: old.totalBytes,
                    speedBytesPerSec = speedBytesPerSec ?: old.speedBytesPerSec,
                    etaSeconds = etaSeconds ?: old.etaSeconds,
                    message = message ?: old.message
                )
            }
        }
    }

    fun failed(id: Int, message: String) {
        mutate { map ->
            val old = map[id]
            if (old != null) {
                map[id] = old.copy(
                    state = DlState.FAILED,
                    message = message
                )
            }
        }
    }

    fun remove(id: Int) {
        mutate { it.remove(id) }
    }

    private inline fun mutate(action: (MutableMap<Int, ActiveDownload>) -> Unit) {
        synchronized(lock) {
            val next = mutableItems.value.toMutableMap()
            action(next)
            mutableItems.value = next.toMap()
        }
    }
}
