package com.sari.tube.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.sari.tube.engine.ActiveDownloads
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
import com.sari.tube.engine.YtDlpUpdater
import com.sari.tube.engine.YtHttp403Exception
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

        fun start(context: Context, input: String, selector: String = DEFAULT_SELECTOR, audioOnly: Boolean = false) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_INPUT, input)
                .putExtra(EXTRA_SELECTOR, selector)
                .putExtra(EXTRA_AUDIO, audioOnly)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, id: Int) {
            val cancelIntent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id)
            context.startService(cancelIntent)
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
        ActiveDownloads.enqueue(id, input)
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
        var succeeded = false
        try {
            waitForSlot(id, control)
            holdsSlot = true
            ActiveDownloads.running(id)

            val url = UrlParser.firstHttpUrl(input)
            if (url != null) downloadSmart(id, input, url, control, selector, audioOnly)
            else downloadDirect(id, input, control)

            succeeded = true
        } catch (e: DownloadCancelledException) {
            ActiveDownloads.remove(id)
            notifier.cancelled(id)
        } catch (e: Exception) {
            Log.e(TAG, "download failed (id=$id, input=$input)", e)
            ActiveDownloads.failed(id, e.message ?: "خطأ غير معروف")
            notifier.failed(id, e.message ?: "خطأ غير معروف")
        } finally {
            if (succeeded) ActiveDownloads.remove(id)
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
        } catch (e: YtHttp403Exception) {
            // Refresh the bundled yt-dlp extractor, then retry the same URL once.
            val updateStatus = YtDlpUpdater.forceUpdate(this)
            Log.w(TAG, "HTTP 403; extractor refresh result: $updateStatus")

            var failure: YtDlpException = e

            if (updateStatus.startsWith("update:")) {
                try {
                    downloadWithYtDlp(id, url, control, selector, audioOnly)
                    return
                } catch (retry: DownloadCancelledException) {
                    throw retry
                } catch (retry: YtBlockedException) {
                    throw retry
                } catch (retry: Exception) {
                    failure = retry as? YtDlpException
                        ?: YtDlpException(retry.message ?: "فشلت إعادة المحاولة بعد تحديث المحرك")
                    Log.w(TAG, "yt-dlp retry failed: ${retry.message}")
                }
            } else {
                failure = YtDlpException(
                    "${e.message} (تعذر تحديث المحرك: $updateStatus)"
                )
            }

            // The fallback is allowed only for a verified direct media URL.
            try {
                downloadDirect(id, input, control)
            } catch (fallback: DownloadCancelledException) {
                throw fallback
            } catch (fallback: Exception) {
                throw IOException(
                    "${failure.message}\nالمحرك الاحتياطي: ${fallback.message}"
                )
            }
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
        ActiveDownloads.updateProgress(
            id,
            title = "جارٍ تجهيز التحميل…",
            progress = -1,
            message = "جارٍ تجهيز محرك التحميل"
        )
        if (!YtDlpEngine.awaitReady()) throw YtDlpException("محرك yt-dlp لم يجهز بعد")

        var last = 0L
        val file = YtDlpDownloader.download(
            url, File(cacheDir, "ytdl"), control, selector, audioOnly
        ) { percent, eta ->
            val now = System.currentTimeMillis()
            if (now - last >= 1000) {
                last = now
                val remaining = if (eta > 0) "  •  متبقٍ ${eta} ث" else ""
                val pct = percent.toInt()
                if (pct >= 0) notifier.percent(id, "تحميل الوسائط", pct, "$pct%$remaining")
                else notifier.progress(id, "تحميل الوسائط", 0, 0, 0, indeterminate = true)
                ActiveDownloads.updateProgress(
                    id,
                    title = "تحميل الوسائط",
                    progress = if (percent >= 0) percent.toInt() else null,
                    etaSeconds = eta,
                    message = remaining
                )
            }
        }
        // The numeric prefix is only an internal resume identifier, not the media title.
        val displayName = file.name.replaceFirst(
            Regex("^${Regex.escape(YtDlpDownloader.idFor(url))}_"), ""
        )
        finishFile(id, file, displayName, YtDlpDownloader.mimeFor(file.name), findCover(url))
        removeYtdlLeftovers(url)
    }

    private suspend fun downloadDirect(id: Int, input: String, control: DownloadControl) {
        val dir = File(cacheDir, "dl").apply { mkdirs() }
        // A stable name per link lets a partial download resume after a failure or restart.
        val temp = File(dir, "dl_${input.trim().hashCode().toUInt()}.bin")

        notifier.progress(id, "جارٍ تحليل الرابط…", 0, 0, 0, indeterminate = true)
        ActiveDownloads.updateProgress(
            id,
            title = "جارٍ تحليل الرابط…",
            progress = -1,
            message = "جارٍ تحليل الرابط"
        )
        var last = 0L
        val chosen = fetchFirstWorking(input, temp, control) { name, downloaded, total, speed ->
            val now = System.currentTimeMillis()
            if (now - last >= 1000) {
                last = now
                notifier.progress(id, name, downloaded, total, speed)
                ActiveDownloads.updateProgress(
                    id,
                    title = name,
                    progress = if (total > 0L) {
                        ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                    } else {
                        -1
                    },
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    speedBytesPerSec = speed,
                    message = "جارٍ تنزيل الملف"
                )
            }
        }
        finishFile(id, temp, chosen.fileName, chosen.mime)
        File(temp.parentFile, temp.name + ".parts").deleteRecursively()
    }

    /** Copies the file to Downloads, records it in history, then removes the temporary copy. */
    /** yt-dlp writes the thumbnail next to the media file. It becomes the cover of the saved item. */
    private fun findCover(url: String): File? {
        val id = YtDlpDownloader.idFor(url)
        return File(cacheDir, "ytdl").listFiles { f ->
            f.extension.lowercase() == "jpg" && (f.name.startsWith("${id}_") || f.name.startsWith("$id."))
        }?.maxByOrNull { it.lastModified() }
    }

    /** Removes the info and partial files yt-dlp leaves after a successful download. */
    private fun removeYtdlLeftovers(url: String) {
        val id = YtDlpDownloader.idFor(url)
        File(cacheDir, "ytdl").listFiles { f ->
            f.name.startsWith("${id}_") || f.name.startsWith("$id.")
        }?.forEach { it.delete() }
    }

    private fun finishFile(id: Int, src: File, name: String, mime: String, cover: File? = null) {
        val uri = PublicSaver.save(this, src, name, mime)
        val rec = runCatching { HistoryStore.add(this, src, name, mime, uri.toString(), cover) }
            .onFailure { Log.w(TAG, "history record failed", it) }
            .getOrNull()
        src.delete()
        cover?.delete()
        notifier.done(id, name, PublicSaver.FOLDER, uri, mime, rec?.thumbPath)
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
                lastError = "لم يتم العثور على رابط ملف وسائط مباشر؛ لن يتم تنزيل صفحة الموقع كأنها فيديو"
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
