package com.sari.tube.engine

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class VideoItem(
    val id: String,
    val title: String,
    val channel: String,
    val durationSec: Long,
    val thumbnail: String?,
    val url: String,
    val views: Long?
)

/** خطأ تُعرض رسالته للمستخدم كما هي. */
class SearchException(message: String) : Exception(message)

object YtDlpSearch {
    private const val TIMEOUT_MS = 45_000L
    private val counter = AtomicLong(0)

    /** يبحث عبر ytsearch20:<query> ويعيد حتى 20 نتيجة. */
    suspend fun search(query: String, count: Int = 20): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!YtDlpEngine.awaitReady()) throw SearchException("محرك البحث لم يجهز بعد، حاول بعد لحظات")
        try {
            runSearch(query, count)
        } catch (e: YoutubeDLException) {
            val ctx = YtDlpEngine.appContext
            if (looksLikeExtractorBreak(e.message) && ctx != null) {
                // غالباً غيّر يوتيوب شيئاً في الاستخراج: نحدّث المستخرج مرة واحدة ثم نعيد المحاولة.
                YtDlpUpdater.forceUpdate(ctx)
                try {
                    runSearch(query, count)
                } catch (retry: YoutubeDLException) {
                    throw SearchException(friendly(retry.message))
                }
            } else {
                throw SearchException(friendly(e.message))
            }
        }
    }

    private suspend fun runSearch(query: String, count: Int): List<VideoItem> = coroutineScope {
        val processId = "search-${counter.incrementAndGet()}"
        val timedOut = AtomicBoolean(false)

        // مراقب: بعد انتهاء المهلة، أو عند إلغاء البحث، يوقف عملية yt-dlp.
        // استدعاء yt-dlp حاجز (blocking) ولا يتوقف بالإلغاء وحده، لذلك نحتاج هذا المراقب.
        val killer = launch(Dispatchers.IO) {
            try {
                delay(TIMEOUT_MS)
                timedOut.set(true)
            } finally {
                destroy(processId)
            }
        }

        val response = try {
            val request = YoutubeDLRequest("ytsearch$count:$query").apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--skip-download")
                addOption("--no-warnings")
            }
            YoutubeDL.getInstance().execute(request, processId)
        } catch (e: YoutubeDLException) {
            if (timedOut.get()) throw SearchException("انتهت مهلة البحث، تحقق من الاتصال")
            throw e
        } finally {
            killer.cancel()
        }

        if (timedOut.get()) throw SearchException("انتهت مهلة البحث، تحقق من الاتصال")
        parse(response.out)
    }

    private fun destroy(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    /** كل سطر في الناتج كائن JSON مستقل. الأسطر التالفة تُتجاهل. */
    private fun parse(out: String): List<VideoItem> {
        val seen = HashSet<String>()
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { line -> runCatching { toItem(JSONObject(line)) }.getOrNull() }
            .filter { seen.add(it.id) }
            .toList()
    }

    private fun toItem(o: JSONObject): VideoItem? {
        val id = o.str("id").ifBlank { return null }
        val title = o.str("title").ifBlank { return null }
        val channel = o.str("channel").ifBlank { o.str("uploader") }
        val duration = if (o.isNull("duration")) 0L else o.optDouble("duration", 0.0).toLong()
        val thumb = o.str("thumbnail").takeIf { it.isNotBlank() }
            ?: bestThumbnail(o.optJSONArray("thumbnails"))
        val url = o.str("webpage_url").ifBlank { "https://www.youtube.com/watch?v=$id" }
        val views = if (o.isNull("view_count")) null else o.optLong("view_count")
        return VideoItem(id, title, channel, duration, thumb, url, views)
    }

    private fun bestThumbnail(arr: JSONArray?): String? {
        if (arr == null) return null
        // القائمة مرتبة من الأصغر إلى الأكبر غالباً، نأخذ آخر عنصر له رابط.
        for (i in arr.length() - 1 downTo 0) {
            val u = arr.optJSONObject(i)?.str("url").orEmpty()
            if (u.isNotBlank()) return u
        }
        return null
    }

    private fun looksLikeExtractorBreak(msg: String?): Boolean {
        val m = msg.orEmpty()
        return listOf("Unable to extract", "Sign in", "nsig", "HTTP Error 403", "Requested format is not available")
            .any { m.contains(it, ignoreCase = true) }
    }

    private fun friendly(msg: String?): String {
        val m = msg.orEmpty()
        return when {
            m.contains("Unable to download", true) || m.contains("timed out", true) ||
                m.contains("Name or service", true) -> "تعذّر الاتصال بيوتيوب، تحقق من الشبكة"
            m.contains("429") || m.contains("Too Many", true) -> "يوتيوب يحد الطلبات مؤقتاً، جرّب بعد قليل"
            else -> "فشل البحث: " + m.lineSequence().firstOrNull().orEmpty().take(160)
        }
    }

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
}
