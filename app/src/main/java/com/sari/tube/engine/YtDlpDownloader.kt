package com.sari.tube.engine

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Failure of the yt-dlp engine. The caller may try the fallback engine. */
open class YtDlpException(message: String) : IOException(message)

/** The site is not known to yt-dlp. Fallback is useful here. */
class YtUnsupportedException(message: String) : YtDlpException(message)

/** Access is blocked (private, removed, login required, region). Other engines will not help. */
class YtBlockedException(message: String) : YtDlpException(message)

enum class YtQuality(val format: String) {
    BEST("bv*+ba/b"),
    P1080("bv*[height<=1080]+ba/b[height<=1080]"),
    P720("bv*[height<=720]+ba/b[height<=720]"),
    P480("bv*[height<=480]+ba/b[height<=480]"),
    AUDIO("ba/b")
}

object YtDlpDownloader {
    private const val TAG = "SariTube"
    private val FRAGMENT_REGEX = Regex("""\.f\d+\.""")
    private val OUTPUT_EXT = setOf("mp4", "mkv", "webm", "m4a", "mp3", "ogg", "opus")

    fun idFor(url: String): String = url.hashCode().toUInt().toString()

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /**
     * Downloads one item to outDir. Blocking: call from Dispatchers.IO.
     * The file name starts with the stable id, so partial downloads resume on the next attempt.
     * If yt-dlp fails only at the final rename step (titles with symbols such as "|" caused this),
     * it retries once with ASCII-only file names.
     */
    fun download(
        url: String,
        outDir: File,
        control: DownloadControl,
        selector: String,
        audioOnly: Boolean,
        onProgress: (percent: Float, etaSec: Long) -> Unit
    ): File {
        outDir.mkdirs()
        val id = idFor(url)
        return try {
            runOnce(url, outDir, id, control, selector, audioOnly, onProgress, asciiNames = false)
        } catch (e: YtDlpException) {
            if (control.isCancelled || e is YtBlockedException || e is YtUnsupportedException) throw e
            if (!e.message.orEmpty().contains("rename", true)) throw e
            Log.w(TAG, "rename failed, retrying with ASCII file names: ${e.message}")
            runOnce(url, outDir, id, control, selector, audioOnly, onProgress, asciiNames = true)
        }
    }

    private fun runOnce(
        url: String,
        outDir: File,
        id: String,
        control: DownloadControl,
        selector: String,
        audioOnly: Boolean,
        onProgress: (percent: Float, etaSec: Long) -> Unit,
        asciiNames: Boolean
    ): File {
        val processId = "dl-$id-${if (asciiNames) "a" else "t"}"
        val template = if (asciiNames) File(outDir, "$id.%(ext)s").absolutePath
        else File(outDir, "${id}_%(title).50s.%(ext)s").absolutePath

        val request = YoutubeDLRequest(url).apply {
            addOption("-f", selector)
            if (audioOnly) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "128K")
            } else {
                addOption("--merge-output-format", "mp4")
            }
            addOption("-o", template)
            if (asciiNames) addOption("--restrict-filenames")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--continue")
            addOption("--newline")
        }

        // yt-dlp does not react to cancellation on its own, so a watcher stops the process.
        val finished = AtomicBoolean(false)
        Thread {
            while (!finished.get()) {
                if (control.isCancelled) {
                    runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                    break
                }
                Thread.sleep(400)
            }
        }.apply { isDaemon = true; start() }

        try {
            YtDlpLock.shared {
                YoutubeDL.getInstance().execute(
                    request,
                    processId,
                    callback = { progress, etaInSeconds, _ ->
                        onProgress(progress, etaInSeconds)
                    }
                )
            }
        } catch (e: YoutubeDLException) {
            if (control.isCancelled) throw DownloadCancelledException()
            val classified = classify(e.message.orEmpty())
            Log.w(TAG, "yt-dlp download failed for $url: ${e.message}")
            throw classified
        } finally {
            finished.set(true)
        }

        if (control.isCancelled) throw DownloadCancelledException()
        return findOutput(outDir, id)
    }

    /** Turns raw yt-dlp output into a message the user can act on, and decides whether to fall back. */
    private fun classify(raw: String): YtDlpException {
        val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(160)
        return when {
            raw.contains("Unsupported URL", true) ->
                YtUnsupportedException("الموقع غير مدعوم من yt-dlp")
            raw.contains("Private video", true) ->
                YtBlockedException("الفيديو خاص ولا يمكن تنزيله")
            raw.contains("Video unavailable", true) || raw.contains("has been removed", true) ->
                YtBlockedException("الفيديو غير متاح أو تم حذفه")
            raw.contains("members only", true) || raw.contains("Sign in", true) ->
                YtBlockedException("المحتوى يتطلب تسجيل دخول، والتطبيق لا يتجاوز ذلك")
            raw.contains("not available in your country", true) ||
                (raw.contains("geo", true) && raw.contains("restrict", true)) ->
                YtBlockedException("المحتوى غير متاح في منطقتك")
            raw.contains("HTTP Error 429", true) ->
                YtDlpException("الموقع يحد الطلبات مؤقتاً، جرّب بعد قليل")
            raw.contains("HTTP Error 403", true) ->
                YtDlpException("رفض الموقع الطلب (403)، وقد يحتاج المحرك إلى تحديث")
            raw.contains("SSL", true) ->
                YtDlpException("تعذّر التحقق من شهادة SSL للموقع")
            else -> YtDlpException("فشل الاستخراج: $firstLine")
        }
    }

    /** Picks the final file and ignores fragments such as .f137.mp4 and .part files. */
    private fun findOutput(dir: File, id: String): File {
        val file = dir.listFiles { f ->
            f.name.startsWith(id) &&
                f.extension.lowercase() in OUTPUT_EXT &&
                !FRAGMENT_REGEX.containsMatchIn(f.name)
        }?.maxByOrNull { it.length() }
            ?: throw YtDlpException("لم يُنتج yt-dlp ملفاً نهائياً")
        if (file.length() < 1024) throw YtDlpException("الملف الناتج صغير جداً وغير صالح")
        return file
    }
}
