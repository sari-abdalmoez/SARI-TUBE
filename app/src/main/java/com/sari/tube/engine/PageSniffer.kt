package com.sari.tube.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
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
    private const val TIMEOUT_MS = 30_000L

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
        val view = withContext(Dispatchers.Main) {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = false
                webViewClient = object : WebViewClient() {
                    // Called on a background thread by the WebView.
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (request != null) {
                            mediaStreamFor(request, pageUrl)?.let { found.complete(it) }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(START_SCRIPT, null)
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

    private fun mediaStreamFor(request: WebResourceRequest, pageUrl: String): PlaybackStream? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val uri = request.url ?: return null
        val full = uri.toString()
        val path = uri.path?.lowercase().orEmpty()
        val isHls = path.endsWith(".m3u8") || full.lowercase().contains(".m3u8?")
        val isFile = isHls || path.endsWith(".mp4") || path.endsWith(".webm") ||
            full.lowercase().contains(".mp4?") || full.lowercase().contains(".webm?")
        if (!isFile) return null

        val headers = LinkedHashMap<String, String>()
        request.requestHeaders?.forEach { (name, value) ->
            if (name.lowercase() !in BLOCKED_HEADERS) headers[name] = value
        }
        headers.putIfAbsent("Referer", pageUrl)
        CookieManager.getInstance().getCookie(full)
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["Cookie"] = it }

        return PlaybackStream(
            url = full,
            headers = headers,
            mimeType = if (isHls) MimeTypes.APPLICATION_M3U8 else null,
            height = 0,
            label = "تلقائي"
        )
    }
}
