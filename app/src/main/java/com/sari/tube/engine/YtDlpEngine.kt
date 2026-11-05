package com.sari.tube.engine

import android.app.Application
import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Keeps yt-dlp updates from running while a search or download uses it.
 * Searches and downloads share the lock (several may run together); an update takes it exclusively.
 */
object YtDlpLock {
    private val rw = ReentrantReadWriteLock()

    fun <T> shared(block: () -> T): T {
        rw.readLock().lock()
        try {
            return block()
        } finally {
            rw.readLock().unlock()
        }
    }

    /** Returns false without waiting if a search or download is running. */
    fun tryExclusive(block: () -> Unit): Boolean {
        if (!rw.writeLock().tryLock()) return false
        try {
            block()
            return true
        } finally {
            rw.writeLock().unlock()
        }
    }
}

/** Initializes yt-dlp and FFmpeg once, in a background thread. Callers wait with awaitReady(). */
object YtDlpEngine {
    private const val TAG = "SariTube"
    private val ready = CompletableDeferred<Boolean>()

    @Volatile
    var appContext: Context? = null
        private set

    fun initializeAsync(app: Application) {
        appContext = app.applicationContext
        Thread({
            val ok = try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                true
            } catch (e: Throwable) {
                Log.e(TAG, "yt-dlp init failed", e)
                false
            }
            ready.complete(ok)
            if (ok) YtDlpUpdater.updateIfDue(app)
        }, "ytdlp-init").start()
    }

    /** Waits up to 90 seconds. Returns false if initialization failed or timed out. */
    suspend fun awaitReady(): Boolean = withTimeoutOrNull(90_000L) { ready.await() } ?: false
}

/** Updates the extractor once every 24 hours, or on demand after a search fails because of a site change. */
object YtDlpUpdater {
    private const val TAG = "SariTube"
    private const val PREFS = "ytdlp_update"
    private const val KEY_LAST = "last_check"
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    fun updateIfDue(app: Context) {
        val last = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last >= INTERVAL_MS) forceUpdate(app)
    }

    /** Returns a short status for the log. Never throws. */
    fun forceUpdate(app: Context): String {
        return try {
            var status: String? = null
            val ran = YtDlpLock.tryExclusive {
                status = YoutubeDL.getInstance().updateYoutubeDL(app)?.toString()
            }
            if (!ran) return "busy: a search or download is running"
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST, System.currentTimeMillis())
                .apply()
            "update: $status"
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update failed", e)
            "update failed: ${e.message}"
        }
    }
}
