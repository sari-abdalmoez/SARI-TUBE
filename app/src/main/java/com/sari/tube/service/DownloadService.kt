package com.sari.tube.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.sari.tube.engine.Candidate
import com.sari.tube.engine.DownloadCancelledException
import com.sari.tube.engine.DownloadControl
import com.sari.tube.engine.DownloadEvent
import com.sari.tube.engine.MediaResolver
import com.sari.tube.engine.SmartDownloader
import com.sari.tube.storage.PublicSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Runs downloads in the background with notifications for progress, completion and location. */
class DownloadService : Service() {
    companion object {
        const val EXTRA_INPUT = "input"
        const val EXTRA_ID = "id"
        const val ACTION_CANCEL = "com.sari.tube.CANCEL"

        fun start(context: Context, input: String) {
            val intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_INPUT, input)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controls = ConcurrentHashMap<Int, DownloadControl>()
    private var nextId = 100
    private lateinit var notifier: DownloadNotifier

    override fun onCreate() {
        super.onCreate()
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

        val id = nextId++
        val control = DownloadControl()
        controls[id] = control
        startForeground(
            DownloadNotifier.FG_ID,
            notifier.foreground(controls.size),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        scope.launch { execute(id, input, control) }
        return START_NOT_STICKY
    }

    private suspend fun execute(id: Int, input: String, control: DownloadControl) {
        val dir = File(cacheDir, "dl").apply { mkdirs() }
        // Stable name so partial segments can be resumed after a failure or restart.
        val temp = File(dir, "dl_${input.trim().hashCode().toUInt()}.bin")
        try {
            notifier.progress(id, "جارٍ تحليل الرابط…", 0, 0, 0, indeterminate = true)
            var last = 0L
            val chosen = fetchFirstWorking(input, temp, control) { name, downloaded, total, speed ->
                val now = System.currentTimeMillis()
                if (now - last >= 1000) {
                    last = now
                    notifier.progress(id, name, downloaded, total, speed)
                }
            }
            val uri = PublicSaver.save(this, temp, chosen.fileName, chosen.mime)
            temp.delete()
            File(temp.parentFile, temp.name + ".parts").deleteRecursively()
            notifier.done(id, chosen.fileName, PublicSaver.FOLDER, uri, chosen.mime)
        } catch (e: DownloadCancelledException) {
            notifier.cancelled(id)
        } catch (e: Exception) {
            notifier.failed(id, e.message ?: "خطأ غير معروف")
        } finally {
            controls.remove(id)
            if (controls.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Tries each candidate in order. A second round re-resolves links that may have expired. */
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
