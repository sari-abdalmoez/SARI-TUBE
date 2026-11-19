package com.sari.tube.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fallback for pages yt-dlp does not understand. The page is loaded in a hidden browser and its
 * video is started. The first video address the page requests (HLS or MP4) is returned, together with
 * the cookies and referrer that browser used, so the player can fetch it the same way.
 */
object PageSniffer {
    private const val TIMEOUT_MS = 18_000L

    private val BLOCKED_HEADERS = setOf(
        "host", "connection", "content-length", "transfer-encoding", "range", "accept-encoding"
    )

    /** Tries to start playback: unmuted videos are muted first so autoplay policies allow it. */
    private const val START_SCRIPT = """
        (function () {
          var tryPlay = function () {
            document.querySelectorAll('video').forEach(function (v) {
              v.muted = true;
              var p = v.play();
              if (p && p.catch) p.catch(function () {});
            });
            var btn = document.querySelector('.vjs-big-play-button, .plyr__control--overlaid, [aria-label*="play" i], [class*="play-button" i]');
            if (btn) { try { btn.click(); } catch (e) {} }
          };
          tryPlay();
          setTimeout(tryPlay, 1500);
          setTimeout(tryPlay, 4000);
        })();
    """

    /** Returns the first video stream the page requests, or null when none appears in time. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(context: Context, pageUrl: String): PlaybackStream? {
        val found = CompletableDeferred<PlaybackStream>()
        val candidates = ConcurrentHashMap<String, PlaybackStream>()
        val completionScheduled = AtomicBoolean(false)

        fun consider(stream: PlaybackStream) {
            candidates[stream.url] = stream
            if (completionScheduled.compareAndSet(false, true)) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val best = candidates.values.maxByOrNull { priority(it) }
                    if (best != null && !found.isCompleted) found.complete(best)
                }, 4_000L)
            }
        }

        val view = withContext(Dispatchers.Main) {
            WebView(context).apply {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    // Called on a background thread by the WebView.
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (request != null) {
                            mediaStreamFor(request, pageUrl)?.let(::consider)
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(START_SCRIPT, null)
                        CookieManager.getInstance().flush()
                    }
                }
                loadUrl(pageUrl)
            }
        }
        try {
            return withTimeoutOrNull(TIMEOUT_MS) { found.await() }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                view.stopLoading()
                view.destroy()
            }
        }
    }

    private fun priority(stream: PlaybackStream): Int {
        val url = stream.url.lowercase()
        val manifestNameBoost = if (
            url.contains("master") || url.contains("playlist") ||
            url.contains("index.m3u8") || url.contains("manifest")
        ) 20 else 0
        return when (stream.mimeType) {
            MimeTypes.APPLICATION_M3U8 -> 400 + manifestNameBoost
            MimeTypes.APPLICATION_MPD -> 300 + manifestNameBoost
            else -> 200
        }
    }

    private fun mediaStreamFor(request: WebResourceRequest, pageUrl: String): PlaybackStream? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val uri = request.url ?: return null
        val full = uri.toString()
        val path = uri.path?.lowercase().orEmpty()
        val lower = full.lowercase()
        // Ignore HLS/DASH media fragments and advertisement chunks. The player needs the
        // playlist/manifest URL, not an individual segment that finishes after a few seconds.
        val fragmentMarkers = listOf(
            ".m4s", ".ts", "/segment/", "/segments/", "/chunk/", "/chunks/",
            "segment-", "chunk-", "/ad/"
        )
        if (fragmentMarkers.any { lower.contains(it) }) return null

        val accept = request.requestHeaders.entries.firstOrNull {
            it.key.equals("Accept", true)
        }?.value.orEmpty()
        val manifestPath = path.contains("manifest") || path.contains("playlist") ||
            path.contains("master") || path.contains("index")
        val isHls = path.endsWith(".m3u8") || lower.contains(".m3u8?") ||
            lower.contains("format=m3u8") ||
            lower.contains("type=application/vnd.apple.mpegurl") ||
            (accept.contains("mpegurl", true) && manifestPath)
        val isDash = path.endsWith(".mpd") || lower.contains(".mpd?") ||
            lower.contains("type=application/dash+xml") ||
            (accept.contains("dash+xml", true) && manifestPath)
        val isFile = isHls || isDash || path.endsWith(".mp4") || path.endsWith(".webm") ||
            lower.contains(".mp4?") || lower.contains(".webm?")
        if (!isFile) return null

        val headers = LinkedHashMap<String, String>()
        request.requestHeaders?.forEach { (name, value) ->
            if (name.lowercase() !in BLOCKED_HEADERS) headers[name] = value
        }
        headers.putIfAbsent("Referer", pageUrl)
        headers.putIfAbsent("User-Agent", request.requestHeaders.entries.firstOrNull {
            it.key.equals("User-Agent", true)
        }?.value ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        CookieManager.getInstance().getCookie(full)
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["Cookie"] = it }

        return PlaybackStream(
            url = full,
            headers = headers,
            mimeType = when { isHls -> MimeTypes.APPLICATION_M3U8; isDash -> MimeTypes.APPLICATION_MPD; else -> null },
            height = 0,
            label = "تلقائي"
        )
    }
}
