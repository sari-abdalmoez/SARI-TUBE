package com.sari.tube.engine

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class PlaybackStream(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?
)

object PlaybackResolver {
    suspend fun resolve(pageUrl: String): PlaybackStream {
        if (!YtDlpEngine.awaitReady()) {
            error("محرك استخراج الفيديو لم يجهز بعد.")
        }

        val output = withContext(Dispatchers.IO) {
            YtDlpLock.shared {
                val request = YoutubeDLRequest(pageUrl).apply {
                    addOption("-f", "best[height<=720]/best")
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--no-progress")
                    addOption("--print", "SARI_URL=%(url)s")
                    addOption("--print", "SARI_HEADERS=%(http_headers)j")
                    addOption("--print", "SARI_PROTOCOL=%(protocol)s")
                }
                YoutubeDL.getInstance().execute(
                    request,
                    "sari-playback-${System.nanoTime()}"
                ).out
            }
        }
        return parseOutput(output)
    }

    private fun parseOutput(output: String): PlaybackStream {
        val lines = output.lineSequence().map { it.trim() }.toList()
        val url = lines.firstOrNull { it.startsWith("SARI_URL=") }
            ?.removePrefix("SARI_URL=")?.trim()
            ?: error("لم يعثر المستخرج على رابط فيديو قابل للتشغيل.")
        val rawHeaders = lines.firstOrNull { it.startsWith("SARI_HEADERS=") }
            ?.removePrefix("SARI_HEADERS=")?.trim().orEmpty()
        val protocol = lines.firstOrNull { it.startsWith("SARI_PROTOCOL=") }
            ?.removePrefix("SARI_PROTOCOL=")?.trim().orEmpty()

        val parsed = URL(url)
        require(parsed.protocol == "http" || parsed.protocol == "https") {
            "نوع رابط الفيديو غير مدعوم."
        }

        val headers = linkedMapOf<String, String>()
        if (rawHeaders.isNotBlank() && rawHeaders != "NA" && rawHeaders != "null") {
            val json = JSONObject(rawHeaders)
            val keys = json.keys()
            val blocked = setOf("host", "connection", "content-length", "transfer-encoding")
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key).trim()
                if (value.isNotBlank() && key.lowercase() !in blocked) {
                    headers[key] = value
                }
            }
        }

        val path = parsed.path.lowercase()
        val mime = when {
            protocol.contains("m3u8", true) || path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            protocol.contains("dash", true) || path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        return PlaybackStream(url, headers, mime)
    }

    @OptIn(UnstableApi::class)
    fun createPlayer(context: Context, stream: PlaybackStream): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
        if (stream.headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(stream.headers)
        }

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()

        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply { stream.mimeType?.let { setMimeType(it) } }
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        return player
    }
}
