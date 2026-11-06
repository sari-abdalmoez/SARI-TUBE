package com.sari.tube.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.sari.tube.engine.AppPrefs
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
import com.sari.tube.engine.YtQuality
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

/** Runs downloads in the background, with notifications for progress, completion and failure. */
class DownloadService : Service() {
    companion object {
        const val EXTRA_INPUT = "input"
        const val EXTRA_ID = "id"
        const val EXTRA_QUALITY = "quality"
        const val ACTION_CANCEL = "com.sari.tube.CANCEL"
        private const val TAG = "SariTube"

        fun start(context: Context, input: String, quality: YtQuality = YtQuality.BEST) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_INPUT, input)
                .putExtra(EXTRA_QUALITY, quality.name)
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
        val quality = runCatching { YtQuality.valueOf(intent.getStringExtra(EXTRA_QUALITY).orEmpty()) }
            .getOrDefault(YtQuality.BEST)

        val id = nextId++
        val control = DownloadControl()
        controls[id] = control
        startForeground(
            DownloadNotifier.FG_ID,
            notifier.foreground(controls.size),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        scope.launch { execute(id, input, control, quality) }
        return START_NOT_STICKY
    }

    private suspend fun execute(id: Int, input: String, control: DownloadControl, quality: YtQuality) {
        var holdsSlot = false
        try {
            waitForSlot(id, control)
            holdsSlot = true

            val url = UrlParser.firstHttpUrl(input)
            if (url != null) downloadSmart(id, input, url, control, quality)
            else downloadDirect(id, input, control)
        } catch (e: DownloadCancelledException) {
            notifier.cancelled(id)
        } catch (e: Exception) {
            Log.e(TAG, "download failed (id=$id, input=$input)", e)
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
     * yt-dlp is tried first for every link. A blocked item stops here, because another engine
     * cannot bypass a private, removed, or login-only item. An unsupported site or an extraction
     * failure falls back to the direct engine.
     */
    private suspend fun downloadSmart(
        id: Int, input: String, url: String, control: DownloadControl, quality: YtQuality
    ) {
        try {
            downloadWithYtDlp(id, url, control, quality)
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

    private suspend fun downloadWithYtDlp(id: Int, url: String, control: DownloadControl, quality: YtQuality) {
        notifier.progress(id, "جارٍ تجهيز التحميل…", 0, 0, 0, indeterminate = true)
        if (!YtDlpEngine.awaitReady()) throw YtDlpException("محرك yt-dlp لم يجهز بعد")

        var last = 0L
        val file = YtDlpDownloader.download(url, File(cacheDir, "ytdl"), control, quality) { percent, eta ->
            val now = System.currentTimeMillis()
            if (now - last >= 1000) {
                last = now
                val remaining = if (eta > 0) "  •  متبقٍ ${eta} ث" else ""
                notifier.percent(id, "تحميل الوسائط", percent.toInt(), "${percent.toInt()}%$remaining")
            }
        }
        finishFile(id, file, file.name, YtDlpDownloader.mimeFor(file.name))
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
                notifier.progress(id, name, downloaded, total, speed)
            }
        }
        finishFile(id, temp, chosen.fileName, chosen.mime)
        File(temp.parentFile, temp.name + ".parts").deleteRecursively()
    }

    /** Copies the file to Downloads, records it in history, then removes the temporary copy. */
    private fun finishFile(id: Int, src: File, name: String, mime: String) {
        val uri = PublicSaver.save(this, src, name, mime)
        // A history failure must not make a successful download look failed.
        runCatching { HistoryStore.add(this, src, name, mime, uri.toString()) }
            .onFailure { Log.w(TAG, "history record failed", it) }
        src.delete()
        notifier.done(id, name, PublicSaver.FOLDER, uri, mime)
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
