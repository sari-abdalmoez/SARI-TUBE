package com.sari.tube.engine

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

enum class YtQuality(val format: String) {
    BEST("bv*+ba/b"),
    P1080("bv*[height<=1080]+ba/b[height<=1080]"),
    P720("bv*[height<=720]+ba/b[height<=720]"),
    P480("bv*[height<=480]+ba/b[height<=480]"),
    AUDIO("ba/b")
}

object YtDlpDownloader {
    private val ID_REGEX = Regex("""(?:v=|youtu\.be/|shorts/|embed/)([A-Za-z0-9_-]{6,})""")
    private val FRAGMENT_REGEX = Regex("""\.f\d+\.""")
    private val OUTPUT_EXT = setOf("mp4", "mkv", "webm", "m4a", "mp3", "ogg", "opus")

    fun isYouTube(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "youtu.be" || host.endsWith("youtube.com")
    }

    fun videoId(url: String): String =
        ID_REGEX.find(url)?.groupValues?.get(1) ?: url.hashCode().toUInt().toString()

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
     * يحمّل فيديو واحداً إلى outDir.
     * الاسم ثابت لكل فيديو، فإذا انقطع التحميل يكمل yt-dlp من ملف .part نفسه عند إعادة المحاولة.
     * يرفع IOException مع رسالة مفهومة، و DownloadCancelledException عند الإلغاء.
     */
    fun download(
        url: String,
        outDir: File,
        control: DownloadControl,
        quality: YtQuality,
        onProgress: (percent: Float, etaSec: Long) -> Unit
    ): File {
        outDir.mkdirs()
        val id = videoId(url)
        val processId = "dl-$id"
        val template = File(outDir, "${id}_%(title)s.%(ext)s").absolutePath

        val request = YoutubeDLRequest(url).apply {
            addOption("-f", quality.format)
            addOption("--merge-output-format", "mp4")
            addOption("-o", template)
            addOption("--trim-filenames", "120")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--continue")
            addOption("--newline")
        }

        // مراقب يوقف العملية عند إلغاء المستخدم. yt-dlp لا يستجيب للإلغاء وحده.
        val finished = AtomicBoolean(false)
        val watcher = Thread {
            while (!finished.get()) {
                if (control.isCancelled) {
                    runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                    break
                }
                Thread.sleep(400)
            }
        }.apply { isDaemon = true; start() }

        try {
            YoutubeDL.getInstance().execute(
                request = request,
                processId = processId,
                redirectErrorStream = false
            ) { progress, etaInSeconds, _ ->
                onProgress(progress, etaInSeconds)
            }
        } catch (e: YoutubeDL.CanceledException) {
            if (control.isCancelled) throw DownloadCancelledException()
            throw IOException("تم إيقاف عملية yt-dlp", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (control.isCancelled) throw DownloadCancelledException()
            throw IOException("تمت مقاطعة التحميل", e)
        } catch (e: YoutubeDLException) {
            if (control.isCancelled) throw DownloadCancelledException()
            throw IOException(friendly(e.message))
        } finally {
            finished.set(true)
        }

        if (control.isCancelled) throw DownloadCancelledException()
        return findOutput(outDir, id)
    }

    /** يختار الملف النهائي ويتجاهل ملفات الأجزاء المؤقتة مثل .f137.mp4 و .part. */
    private fun findOutput(dir: File, id: String): File {
        val file = dir.listFiles { f ->
            f.name.startsWith("${id}_") &&
                f.extension.lowercase() in OUTPUT_EXT &&
                !FRAGMENT_REGEX.containsMatchIn(f.name)
        }?.maxByOrNull { it.length() }
            ?: throw IOException("لم يُنتج yt-dlp ملفاً نهائياً. ربما الفيديو غير متاح أو يحتاج تسجيل دخول")
        if (file.length() < 1024) throw IOException("الملف الناتج صغير جداً وغير صالح")
        return file
    }

    private fun friendly(msg: String?): String =
        "فشل التحميل: " + msg.orEmpty().lineSequence().firstOrNull().orEmpty().take(180)
}
