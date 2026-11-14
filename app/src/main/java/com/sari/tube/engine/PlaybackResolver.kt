package com.sari.tube.engine

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

/** One playable quality of a page. Headers are the ones the host requires for this exact URL. */
data class PlaybackStream(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val height: Int,
    val label: String
)

/** All qualities found for one page. [streams] is never empty and is sorted best first. */
data class PlaybackSession(
    val title: String,
    val streams: List<PlaybackStream>,
    val defaultIndex: Int
)

/** Error whose message is shown to the user as is (in Arabic). */
class ResolveException(message: String) : Exception(message)

object PlaybackResolver {
    private const val TIMEOUT_MS = 60_000L
    private const val MAX_HEIGHT = 1080
    private const val DEFAULT_MAX_HEIGHT = 720
    private val counter = AtomicLong(0)

    /**
     * Headers that must not be forwarded to the player. ExoPlayer sets Range itself (seeking would
     * break with a fixed Range), and Accept-Encoding would make it receive compressed bytes it cannot read.
     */
    private val BLOCKED_HEADERS = setOf(
        "host", "connection", "content-length", "transfer-encoding", "range", "accept-encoding"
    )

    /** Extracts the real media URLs of a page with yt-dlp. Runs in the background and is killed on timeout. */
    suspend fun resolve(pageUrl: String): PlaybackSession {
        if (!YtDlpEngine.awaitReady()) {
            throw ResolveException("محرك استخراج الفيديو لم يجهز بعد، حاول بعد لحظات.")
        }
        val processId = "playback-${counter.incrementAndGet()}"
        val raw = withContext(Dispatchers.IO) {
            coroutineScope {
                val timedOut = AtomicBoolean(false)
                val killer = launch(Dispatchers.IO) {
                    delay(TIMEOUT_MS)
                    timedOut.set(true)
                    runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                }
                try {
                    val request = YoutubeDLRequest(pageUrl).apply {
                        // نطلب كل التنسيقات ونختار بأنفسنا، لأن أي تصفية هنا تُخفي جودات أخرى.
                        addOption("--dump-single-json")
                        addOption("--skip-download")
                        addOption("--no-playlist")
                        addOption("--no-warnings")
                        addOption("--no-progress")
                    }
                    YtDlpLock.shared { YoutubeDL.getInstance().execute(request, processId).out }
                } catch (e: YoutubeDLException) {
                    if (timedOut.get()) throw ResolveException("انتهت مهلة استخراج الفيديو. تحقق من الاتصال ثم أعد المحاولة.")
                    throw ResolveException(friendlyExtractorError(e.message))
                } finally {
                    killer.cancel()
                }
            }
        }
        if (raw.isBlank()) throw ResolveException("لم يُرجع المستخرج أي بيانات لهذا الرابط.")
        return parse(raw)
    }

    /** Parses yt-dlp's JSON. Only formats that carry audio and video together are kept, so the player needs no merge. */
    internal fun parse(raw: String): PlaybackSession {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw ResolveException("لم يُرجع المستخرج بيانات صالحة للفيديو.")
        val root = JSONObject(raw.substring(start, end + 1))

        val title = root.optString("title").ifBlank { "فيديو" }
        val rootHeaders = headersOf(root.optJSONObject("http_headers"))
        val formats: JSONArray = root.optJSONArray("formats") ?: JSONArray()

        // key: height, value: best candidate for that height
        val byHeight = HashMap<Int, Pair<Int, PlaybackStream>>()
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val url = f.optString("url")
            if (!(url.startsWith("https://") || url.startsWith("http://"))) continue
            if (!hasCodec(f, "vcodec") || !hasCodec(f, "acodec")) continue

            val protocol = f.optString("protocol")
            if (protocol.startsWith("http_dash") || protocol == "mhtml") continue

            val height = f.optInt("height", 0)
            if (height > MAX_HEIGHT) continue

            val isHls = protocol.contains("m3u8") || url.contains(".m3u8")
            val ext = f.optString("ext")
            // Progressive MP4 is the most reliable for ExoPlayer, so it wins over HLS on equal heights.
            val rank = (if (isHls) 0 else 2) + (if (ext == "mp4") 1 else 0)

            val headers = LinkedHashMap(rootHeaders)
            headers.putAll(headersOf(f.optJSONObject("http_headers")))

            val stream = PlaybackStream(
                url = url,
                headers = headers,
                mimeType = if (isHls) MimeTypes.APPLICATION_M3U8 else null,
                height = height,
                label = if (height > 0) "${height}p" else "أفضل جودة"
            )
            val current = byHeight[height]
            if (current == null || rank > current.first) byHeight[height] = rank to stream
        }

        if (byHeight.isEmpty()) {
            throw ResolveException("لا يوجد تنسيق فيديو مدمج قابل للتشغيل لهذا الرابط.")
        }

        val streams = byHeight.values
            .map { it.second }
            .sortedWith(compareByDescending<PlaybackStream> { it.height })

        // Start at 720p or lower when available, to keep data use reasonable on phones.
        val defaultIndex = streams.indexOfFirst { it.height in 1..DEFAULT_MAX_HEIGHT }
            .takeIf { it >= 0 } ?: 0

        return PlaybackSession(title, streams, defaultIndex)
    }

    private fun hasCodec(f: JSONObject, key: String): Boolean {
        val v = f.optString(key)
        return v.isNotBlank() && v != "none" && v != "null"
    }

    private fun headersOf(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.optString(key).trim()
            if (value.isNotBlank() && key.lowercase() !in BLOCKED_HEADERS) out[key] = value
        }
        return out
    }

    private fun friendlyExtractorError(message: String?): String {
        val m = message.orEmpty()
        return when {
            m.contains("private", true) || m.contains("Sign in", true) ->
                "هذا الفيديو خاص أو يتطلب تسجيل دخول."
            m.contains("not available", true) || m.contains("unavailable", true) ->
                "الفيديو غير متاح حالياً أو محذوف."
            m.contains("Unsupported URL", true) ->
                "هذا الموقع غير مدعوم حالياً."
            else -> "تعذر استخراج الفيديو من الرابط. ${m.take(120)}".trim()
        }
    }

    /** Maps a player error to a message the user can act on. */
    fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "تعذر الاتصال بمصدر الفيديو. تحقق من الإنترنت ثم أعد المحاولة."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "رفض المصدر طلب الفيديو، وربما انتهت صلاحية الرابط. أعد المحاولة."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
            "صيغة هذا الفيديو غير مدعومة في المشغل. جرّب جودة أخرى."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "المصدر أرسل ملفاً تالفاً. أعد المحاولة."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "جهازك لا يستطيع فك ترميز هذه الجودة. جرّب جودة أقل."
        else -> "حدث خطأ أثناء تشغيل الفيديو (${e.errorCodeName})."
    }

    /** True when the link itself was rejected, which usually means it expired and a new extraction helps. */
    fun isExpiredLink(e: PlaybackException): Boolean =
        e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

    @OptIn(UnstableApi::class)
    fun createPlayer(context: Context, stream: PlaybackStream, startMs: Long = 0L): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        if (stream.headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(stream.headers)
        }
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply { stream.mimeType?.let { setMimeType(it) } }
            .build()
        player.setMediaItem(item, startMs)
        player.prepare()
        player.playWhenReady = true
        return player
    }

    /** Player for a file on the device (downloads and local videos). */
    fun createLocalPlayer(context: Context, uri: String, startMs: Long = 0L): ExoPlayer {
        val player = ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
        player.setMediaItem(MediaItem.fromUri(uri), startMs)
        player.prepare()
        player.playWhenReady = true
        return player
    }
}
