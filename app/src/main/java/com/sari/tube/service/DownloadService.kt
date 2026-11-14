package com.sari.tube.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.sari.tube.engine.ActiveDownload
import com.sari.tube.engine.ActiveDownloads
import com.sari.tube.engine.AppPrefs
import com.sari.tube.engine.DlState
import com.sari.tube.engine.Candidate
import com.sari.tube.engine.DownloadCancelledException
import com.sari.tube.engine.DownloadControl
import com.sari.tube.engine.DownloadEvent
import com.sari.tube.engine.MediaResolver
import com.sari.tube.engine.SmartDownloader
import com.sari.tube.engine.UrlParser
import com.sari.tube.engine.YtBlockedException
import com.sari.tube.engine.YtDlpDownloader
import com.sari.tube.engine.YtDlpEngine
import com.sari.tube.engine.YtDlpException
import com.sari.tube.storage.HistoryStore
import com.sari.tube.storage.PublicSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background downloads with notifications. A link is downloaded through yt-dlp using the format the
 * user chose (audio or a video height). The direct engine is used only when yt-dlp cannot handle the site.
 */
class DownloadService : Service() {
    companion object {
        const val EXTRA_INPUT = "input"
        const val EXTRA_ID = "id"
        const val EXTRA_SELECTOR = "selector"
        const val EXTRA_AUDIO = "audio"
        const val ACTION_CANCEL = "com.sari.tube.CANCEL"
        private const val TAG = "SariTube"
        private const val DEFAULT_SELECTOR = "bv*+ba/b"

        /** Asks the running service to cancel one download. */
        fun cancel(context: Context, id: Int) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_ID, id)
            )
        }

        fun start(context: Context, input: String, selector: String = DEFAULT_SELECTOR, audioOnly: Boolean = false) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_INPUT, input)
                .putExtra(EXTRA_SELECTOR, selector)
                .putExtra(EXTRA_AUDIO, audioOnly)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controls = ConcurrentHashMap<Int, DownloadControl>()
    private val activeSlots = AtomicInteger(0)
    private var nextId = 100
    private lateinit var notifier: DownloadNotifier

    override fun onCreate() {
        super.onCreate()
        AppPrefs.applyRate(this)
        notifier = DownloadNotifier(this)
        notifier.createChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            controls[intent.getIntExtra(EXTRA_ID, -1)]?.cancel()
            return START_NOT_STICKY
        }
        val input = intent?.getStringExtra(EXTRA_INPUT)?.takeIf { it.isNotBlank() }
            ?: return START_NOT_STICKY
        val selector = intent.getStringExtra(EXTRA_SELECTOR)?.takeIf { it.isNotBlank() } ?: DEFAULT_SELECTOR
        val audioOnly = intent.getBooleanExtra(EXTRA_AUDIO, false)

        val id = nextId++
        val control = DownloadControl()
        controls[id] = control
        startForeground(
            DownloadNotifier.FG_ID,
            notifier.foreground(controls.size),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        scope.launch { execute(id, input, control, selector, audioOnly) }
        return START_NOT_STICKY
    }

    private suspend fun execute(
        id: Int, input: String, control: DownloadControl, selector: String, audioOnly: Boolean
    ) {
        var holdsSlot = false
        ActiveDownloads.put(queuedState(id, input))
        try {
            waitForSlot(id, control)
            holdsSlot = true
            ActiveDownloads.modify(id) { it.copy(state = DlState.RUNNING, message = "جارٍ التحميل") }

            val url = UrlParser.firstHttpUrl(input)
            if (url != null) downloadSmart(id, input, url, control, selector, audioOnly)
            else downloadDirect(id, input, control)
        } catch (e: DownloadCancelledException) {
            ActiveDownloads.remove(id)
            notifier.cancelled(id)
        } catch (e: Exception) {
            Log.e(TAG, "download failed (id=$id, input=$input)", e)
            ActiveDownloads.modify(id) { it.copy(state = DlState.FAILED, message = e.message ?: "خطأ غير معروف") }
            notifier.failed(id, e.message ?: "خطأ غير معروف")
        } finally {
            if (holdsSlot) activeSlots.decrementAndGet()
            controls.remove(id)
            if (controls.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * yt-dlp first. A blocked item stops here (no other engine bypasses private or login-only items).
     * An unsupported site or an extraction failure falls back to the direct engine.
     */
    private suspend fun downloadSmart(
        id: Int, input: String, url: String, control: DownloadControl, selector: String, audioOnly: Boolean
    ) {
        try {
            downloadWithYtDlp(id, url, control, selector, audioOnly)
        } catch (e: DownloadCancelledException) {
            throw e
        } catch (e: YtBlockedException) {
            throw e
        } catch (e: YtDlpException) {
            Log.w(TAG, "yt-dlp failed, using fallback engine: ${e.message}")
            try {
                downloadDirect(id, input, control)
            } catch (fallback: DownloadCancelledException) {
                throw fallback
            } catch (fallback: Exception) {
                throw IOException("${e.message}\nالمحرك الاحتياطي: ${fallback.message}")
            }
        }
    }

    private suspend fun downloadWithYtDlp(
        id: Int, url: String, control: DownloadControl, selector: String, audioOnly: Boolean
    ) {
        notifier.progress(id, "جارٍ تجهيز التحميل…", 0, 0, 0, indeterminate = true)
        if (!YtDlpEngine.awaitReady()) throw YtDlpException("محرك yt-dlp لم يجهز بعد")

        var last = 0L
        val file = YtDlpDownloader.download(
            url, File(cacheDir, "ytdl"), control, selector, audioOnly
        ) { percent, eta ->
            val now = System.currentTimeMillis()
            if (now - last >= 1000) {
                last = now
                val remaining = if (eta > 0) "  •  متبقٍ ${eta} ث" else ""
                ActiveDownloads.modify(id) { it.copy(percent = percent.toInt(), etaSec = eta.toLong(), message = "جارٍ التحميل") }
                notifier.percent(id, "تحميل الوسائط", percent.toInt(), "${percent.toInt()}%$remaining")
            }
        }
        // The numeric prefix is only an internal resume identifier, not the media title.
        val displayName = file.name.replaceFirst(
            Regex("^${Regex.escape(YtDlpDownloader.idFor(url))}_"), ""
        )
        finishFile(id, file, displayName, YtDlpDownloader.mimeFor(file.name))
        removeYtdlLeftovers(url)
    }

    private suspend fun downloadDirect(id: Int, input: String, control: DownloadControl) {
        val dir = File(cacheDir, "dl").apply { mkdirs() }
        // A stable name per link lets a partial download resume after a failure or restart.
        val temp = File(dir, "dl_${input.trim().hashCode().toUInt()}.bin")

        notifier.progress(id, "جارٍ تحليل الرابط…", 0, 0, 0, indeterminate = true)
        var last = 0L
        val chosen = fetchFirstWorking(input, temp, control) { name, downloaded, total, speed ->
            val now = System.currentTimeMillis()
            if (now - last >= 1000) {
                last = now
                ActiveDownloads.modify(id) {
                    it.copy(
                        title = name,
                        percent = if (total > 0) (downloaded * 100 / total).toInt() else -1,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBps = speed,
                        message = "جارٍ التحميل"
                    )
                }
                notifier.progress(id, name, downloaded, total, speed)
            }
        }
        finishFile(id, temp, chosen.fileName, chosen.mime)
        File(temp.parentFile, temp.name + ".parts").deleteRecursively()
    }

    private fun queuedState(id: Int, input: String) = ActiveDownload(
        id = id,
        title = input.trim().take(60),
        state = DlState.QUEUED,
        percent = -1,
        downloadedBytes = 0L,
        totalBytes = -1L,
        speedBps = -1L,
        etaSec = -1L,
        message = "في الانتظار"
    )

    /** yt-dlp writes thumbnails and info files next to the video. Remove them after success. */
    private fun removeYtdlLeftovers(url: String) {
        val id = YtDlpDownloader.idFor(url)
        File(cacheDir, "ytdl").listFiles { f ->
            f.name.startsWith("${id}_") || f.name.startsWith("$id.")
        }?.forEach { it.delete() }
    }

    /** Copies the file to Downloads, records it in history, then removes the temporary copy. */
    private fun finishFile(id: Int, src: File, name: String, mime: String) {
        val uri = PublicSaver.save(this, src, name, mime)
        runCatching { HistoryStore.add(this, src, name, mime, uri.toString()) }
            .onFailure { Log.w(TAG, "history record failed", it) }
        src.delete()
        notifier.done(id, name, PublicSaver.FOLDER, uri, mime)
        ActiveDownloads.remove(id)
    }

    /** Waits in the queue until fewer downloads than the chosen limit are running. */
    private suspend fun waitForSlot(id: Int, control: DownloadControl) {
        var announced = false
        while (true) {
            if (control.isCancelled) throw DownloadCancelledException()
            val limit = AppPrefs.maxParallel(this)
            val current = activeSlots.get()
            if (current < limit && activeSlots.compareAndSet(current, current + 1)) return
            if (!announced) {
                notifier.progress(id, "في قائمة الانتظار…", 0, 0, 0, indeterminate = true)
                announced = true
            }
            delay(500)
        }
    }

    /** Tries each candidate in order. The second round re-resolves links that may have expired. */
    private fun fetchFirstWorking(
        input: String,
        temp: File,
        control: DownloadControl,
        onProgress: (name: String, downloaded: Long, total: Long, speed: Long) -> Unit
    ): Candidate {
        var lastError = "لم يتم العثور على فيديو قابل للتحميل في هذا الرابط"
        for (round in 1..2) {
            val candidates = MediaResolver.candidates(input)
            if (candidates.isEmpty()) {
                lastError = "الرابط غير صالح"
                break
            }
            for (c in candidates) {
                if (control.isCancelled) throw DownloadCancelledException()
                try {
                    SmartDownloader(extraHeaders = c.headers).download(c.url, temp, control) { ev ->
                        if (ev is DownloadEvent.Progress) onProgress(c.fileName, ev.downloaded, ev.total, ev.bytesPerSec)
                    }
                    return c
                } catch (e: DownloadCancelledException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "direct candidate ${c.label} failed: ${e.message}")
                    lastError = "${c.label}: ${e.message ?: "فشل"}"
                }
            }
            if (round == 1) Thread.sleep(2000)
        }
        throw IOException(lastError)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
