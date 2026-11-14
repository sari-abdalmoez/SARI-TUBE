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
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
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
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One playable quality. When [audioUrl] is set, the video and the audio come from two addresses
 * (many sites serve them separately) and the player merges them.
 */
data class PlaybackStream(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val height: Int,
    val label: String,
    val audioUrl: String? = null,
    val audioHeaders: Map<String, String> = emptyMap(),
    val audioMime: String? = null
)

/** All qualities found for one page. [streams] is never empty and [defaultIndex] points at the one to start with. */
data class PlaybackSession(
    val title: String,
    val streams: List<PlaybackStream>,
    val defaultIndex: Int
)

/**
 * Failure shown to the user in Arabic. [canRetryWithBrowser] is true when the page may still hold
 * a video that a browser can reveal. It is false for DRM, login-only, private or removed content.
 */
class ResolveException(message: String, val canRetryWithBrowser: Boolean = false) : Exception(message)

object PlaybackResolver {
    private const val TIMEOUT_MS = 60_000L
    private const val MAX_HEIGHT = 1080
    private const val DEFAULT_MAX_HEIGHT = 720
    private val counter = AtomicLong(0)

    /**
     * Headers not forwarded to the player. ExoPlayer sets Range itself (a fixed Range breaks seeking),
     * and Accept-Encoding makes the server send compressed bytes that the player cannot read.
     */
    private val BLOCKED_HEADERS = setOf(
        "host", "connection", "content-length", "transfer-encoding", "range", "accept-encoding"
    )

    /**
     * Resolves a page to playable streams. yt-dlp is tried first. If it cannot understand the page,
     * the page is opened in a hidden browser that reports the video it actually requests.
     */
    suspend fun resolve(context: Context, pageUrl: String): PlaybackSession =
        try {
            extract(pageUrl)
        } catch (e: ResolveException) {
            if (!e.canRetryWithBrowser) throw e
            val stream = PageSniffer.sniff(context, pageUrl)
                ?: throw ResolveException("لم نجد فيديواً قابلاً للتشغيل في هذه الصفحة. جرّب نتيجة أخرى.")
            PlaybackSession(
                title = hostOf(pageUrl),
                streams = listOf(stream),
                defaultIndex = 0
            )
        }

    private suspend fun extract(pageUrl: String): PlaybackSession {
        if (!YtDlpEngine.awaitReady()) {
            throw ResolveException("محرك استخراج الفيديو لم يجهز بعد، حاول بعد لحظات.", canRetryWithBrowser = false)
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
                        // Ask for every format and choose here. A filter inside yt-dlp would hide the others.
                        addOption("--dump-single-json")
                        addOption("--skip-download")
                        addOption("--no-playlist")
                        addOption("--no-warnings")
                        addOption("--no-progress")
                    }
                    YtDlpLock.shared { YoutubeDL.getInstance().execute(request, processId).out }
                } catch (e: YoutubeDLException) {
                    if (timedOut.get()) {
                        throw ResolveException("انتهت مهلة استخراج الفيديو. تحقق من الاتصال ثم أعد المحاولة.")
                    }
                    throw classify(e.message)
                } finally {
                    killer.cancel()
                }
            }
        }
        if (raw.isBlank()) throw ResolveException("لم يُرجع المستخرج أي بيانات لهذه الصفحة.", canRetryWithBrowser = true)
        return parse(raw)
    }

    /** Parses yt-dlp's JSON and builds the list of qualities. */
    internal fun parse(raw: String): PlaybackSession {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw ResolveException("لم يُرجع المستخرج بيانات صالحة للفيديو.", canRetryWithBrowser = true)
        }
        val root = JSONObject(raw.substring(start, end + 1))
        val title = root.optString("title").ifBlank { "فيديو" }
        val rootHeaders = headersOf(root.optJSONObject("http_headers"))
        val formats: JSONArray = root.optJSONArray("formats") ?: JSONArray()

        // Complete formats (video and audio in one file), keyed by height.
        val muxed = HashMap<Int, Pair<Int, PlaybackStream>>()
        // Video-only formats and audio-only formats, used when the site serves them separately.
        val videoOnly = HashMap<Int, Pair<Int, JSONObject>>()
        val audioOnly = ArrayList<Pair<Double, JSONObject>>()

        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val url = f.optString("url")
            if (!(url.startsWith("https://") || url.startsWith("http://"))) continue
            val protocol = f.optString("protocol")
            if (protocol.startsWith("http_dash") || protocol == "mhtml") continue

            val hasVideo = hasCodec(f, "vcodec")
            val hasAudio = hasCodec(f, "acodec")
            val height = f.optInt("height", 0)
            if (height > MAX_HEIGHT) continue

            val isHls = protocol.contains("m3u8") || url.contains(".m3u8")
            val ext = f.optString("ext")
            // Progressive MP4 is the most reliable for ExoPlayer, so it wins over HLS on the same height.
            val rank = (if (isHls) 0 else 2) + (if (ext == "mp4") 1 else 0)

            when {
                hasVideo && hasAudio -> {
                    val stream = PlaybackStream(
                        url = url,
                        headers = mergedHeaders(rootHeaders, f),
                        mimeType = if (isHls) MimeTypes.APPLICATION_M3U8 else null,
                        height = height,
                        label = labelFor(height)
                    )
                    val current = muxed[height]
                    if (current == null || rank > current.first) muxed[height] = rank to stream
                }
                hasVideo && !hasAudio && height > 0 -> {
                    val current = videoOnly[height]
                    if (current == null || rank > current.first) videoOnly[height] = rank to f
                }
                !hasVideo && hasAudio -> {
                    val score = f.optDouble("abr", 0.0) + if (ext == "m4a" || ext == "mp4") 1000.0 else 0.0
                    audioOnly += score to f
                }
            }
        }

        val streams = ArrayList<PlaybackStream>()
        streams += muxed.values.map { it.second }

        // Separate video and audio: pair each video height that has no complete version with the best audio.
        val audio = audioOnly.maxByOrNull { it.first }?.second
        if (audio != null) {
            val audioUrl = audio.optString("url")
            val audioIsHls = audio.optString("protocol").contains("m3u8") || audioUrl.contains(".m3u8")
            for ((height, candidate) in videoOnly) {
                if (muxed.containsKey(height)) continue
                val v = candidate.second
                val videoUrl = v.optString("url")
                val videoIsHls = v.optString("protocol").contains("m3u8") || videoUrl.contains(".m3u8")
                streams += PlaybackStream(
                    url = videoUrl,
                    headers = mergedHeaders(rootHeaders, v),
                    mimeType = if (videoIsHls) MimeTypes.APPLICATION_M3U8 else null,
                    height = height,
                    label = labelFor(height),
                    audioUrl = audioUrl,
                    audioHeaders = mergedHeaders(rootHeaders, audio),
                    audioMime = if (audioIsHls) MimeTypes.APPLICATION_M3U8 else null
                )
            }
        }

        if (streams.isEmpty()) {
            throw ResolveException("لم نجد تنسيقاً قابلاً للتشغيل في هذه الصفحة.", canRetryWithBrowser = true)
        }

        val sorted = streams.sortedWith(compareByDescending<PlaybackStream> { it.height })
        // Start at 720p or lower when available, to keep data use reasonable on phones.
        val defaultIndex = sorted.indexOfFirst { it.height in 1..DEFAULT_MAX_HEIGHT }.takeIf { it >= 0 } ?: 0
        return PlaybackSession(title, sorted, defaultIndex)
    }

    private fun labelFor(height: Int): String = if (height > 0) "${height}p" else "أفضل جودة"

    private fun mergedHeaders(root: Map<String, String>, format: JSONObject): Map<String, String> {
        val out = LinkedHashMap(root)
        out.putAll(headersOf(format.optJSONObject("http_headers")))
        return out
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

    /** Turns yt-dlp's English error into an Arabic message, and says whether a browser can still help. */
    private fun classify(message: String?): ResolveException {
        val m = message.orEmpty()
        return when {
            m.contains("DRM", ignoreCase = false) ->
                ResolveException("هذا الموقع محمي بتقنية DRM، ولا يمكن تشغيله داخل التطبيق.")
            m.contains("private", true) || m.contains("sign in", true) || m.contains("log in", true) ->
                ResolveException("هذا المحتوى خاص أو يتطلب تسجيل دخول.")
            m.contains("not available", true) || m.contains("unavailable", true) || m.contains("removed", true) ->
                ResolveException("المحتوى غير متاح حالياً أو تمت إزالته.")
            else -> ResolveException("لم يتعرف المستخرج على فيديو في هذه الصفحة.", canRetryWithBrowser = true)
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

    /** True when the link itself was rejected, which usually means it expired and extracting again helps. */
    fun isExpiredLink(e: PlaybackException): Boolean =
        e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

    @OptIn(UnstableApi::class)
    fun createPlayer(context: Context, stream: PlaybackStream, startMs: Long = 0L): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val headers = stream.headers + stream.audioHeaders
        if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)

        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
        val video = mediaSource(factory, stream.url, stream.mimeType)
        val source: MediaSource = stream.audioUrl?.let { audioUrl ->
            MergingMediaSource(video, mediaSource(factory, audioUrl, stream.audioMime))
        } ?: video

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(factory)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
        player.setMediaSource(source, startMs)
        player.prepare()
        player.playWhenReady = true
        return player
    }

    private fun mediaSource(factory: DefaultMediaSourceFactory, url: String, mime: String?): MediaSource {
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { mime?.let { setMimeType(it) } }
            .build()
        return factory.createMediaSource(item)
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

    private fun hostOf(url: String): String =
        runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}
