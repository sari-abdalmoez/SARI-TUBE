package com.sari.tube.engine

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** One choice the user can download: a video height or an audio file. */
data class MediaOption(
    val id: String,
    val label: String,
    val isAudio: Boolean,
    val sizeBytes: Long?,
    val selector: String
)

/**
 * Reads the real formats of a link with yt-dlp (--dump-json) and turns them into options.
 * Only formats the site actually provides are listed; sizes come from the site when it reports them.
 */
object MediaFormats {
    private val counter = AtomicLong(0)
    private val VIDEO_TIERS = listOf(360, 480, 720, 1080)

    private data class Fmt(
        val formatId: String,
        val ext: String,
        val height: Int,
        val size: Long?,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val abr: Double
    )

    suspend fun fetch(url: String): List<MediaOption> = withContext(Dispatchers.IO) {
        if (!YtDlpEngine.awaitReady()) throw SearchException("محرك yt-dlp لم يجهز بعد، حاول لاحقاً")
        val processId = "fmt-${counter.incrementAndGet()}"
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--skip-download")
        }
        val out = try {
            YtDlpLock.shared { YoutubeDL.getInstance().execute(request, processId).out }
        } catch (e: Exception) {
            throw SearchException("تعذّر قراءة التنسيقات: ${e.message?.lineSequence()?.firstOrNull().orEmpty().take(160)}")
        }
        parse(out)
    }

    fun parse(out: String): List<MediaOption> {
        val line = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("{") }
            ?: return fallback()
        val formats: JSONArray = JSONObject(line).optJSONArray("formats") ?: return fallback()

        val fmts = ArrayList<Fmt>()
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val hasV = f.s("vcodec").let { it.isNotBlank() && it != "none" }
            val hasA = f.s("acodec").let { it.isNotBlank() && it != "none" }
            if (!hasV && !hasA) continue // storyboards and images
            val size = sequenceOf("filesize", "filesize_approx")
                .mapNotNull { k -> if (f.isNull(k)) null else f.optLong(k, 0L).takeIf { it > 0 } }
                .firstOrNull()
            fmts.add(
                Fmt(
                    formatId = f.s("format_id"),
                    ext = f.s("ext").ifBlank { "audio" },
                    height = f.optInt("height", 0),
                    size = size,
                    hasVideo = hasV,
                    hasAudio = hasA,
                    abr = f.optDouble("abr", 0.0)
                )
            )
        }

        // Keep one real downloadable format for each available audio bitrate.
        // Prefer M4A when the source offers multiple containers at the same bitrate.
        val audioChoices = fmts
            .filter { it.hasAudio && !it.hasVideo && it.formatId.isNotBlank() }
            .groupBy { if (it.abr > 0) it.abr.roundToInt() else 0 }
            .values
            .mapNotNull { group ->
                group.maxWithOrNull(
                    compareBy<Fmt>(
                        { if (it.ext.equals("m4a", true)) 1 else 0 },
                        { it.size ?: 0L }
                    )
                )
            }
            .sortedWith(compareBy<Fmt>({ it.abr }, { it.size ?: 0L }))

        // Select the best real audio-only format for combined video size estimation.
        val bestAudio = audioChoices.maxWithOrNull(
            compareBy<Fmt>({ it.abr }, { it.size ?: 0L })
        )

        val result = ArrayList<MediaOption>()
        audioChoices.forEachIndexed { index, audio ->
            val bitrate = audio.abr.roundToInt()
            val label = if (bitrate > 0) {
                "$bitrate kbps · ${audio.ext.uppercase(Locale.ROOT)}"
            } else {
                "صوت · ${audio.ext.uppercase(Locale.ROOT)}"
            }
            result.add(
                MediaOption(
                    id = "audio_$index",
                    label = label,
                    isAudio = true,
                    sizeBytes = audio.size,
                    selector = audio.formatId
                )
            )
        }

        for (tier in VIDEO_TIERS) {
            val minH = (tier * 0.9).toInt()
            val best = fmts.filter { it.hasVideo && it.height in minH..tier }
                .maxWithOrNull(compareBy<Fmt>({ it.height }, { it.size ?: 0L })) ?: continue
            val audioNeeded = !best.hasAudio
            val total: Long? = when {
                best.size == null -> null
                audioNeeded -> best.size + (bestAudio?.size ?: 0L)
                else -> best.size
            }
            result.add(MediaOption("v$tier", "${tier}p", false, total, "b[height<=$tier][ext=mp4]/bv*[height<=$tier]+ba[ext=m4a]/bv*[height<=$tier]+ba/b[height<=$tier]"))
        }

        return if (result.isEmpty()) fallback() else result
    }

    /** When the site reports no usable list, offer a single best-quality download instead of inventing sizes. */
    private fun fallback(): List<MediaOption> =
        listOf(MediaOption("best", "أفضل جودة متاحة", false, null, "bv*+ba/b"))

    private fun JSONObject.s(key: String): String = if (isNull(key)) "" else optString(key)
}
