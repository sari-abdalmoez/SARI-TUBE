package com.sari.tube.ui.player

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Blocks requests to well-known advertising and tracking hosts only. Media requests (playlists,
 * segments, video and audio files) are never blocked, and neither is the page's own host.
 */
internal object EmbeddedBrowserGuard {
    private val AD_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "scorecardresearch.com",
        "adsterra.com", "exoclick.com", "exosrv.com", "popads.net", "popcash.net", "propellerads.com",
        "juicyads.com", "trafficjunky.com", "adnxs.com", "taboola.com", "outbrain.com",
        "moatads.com", "amazon-adsystem.com", "histats.com", "adcash.com"
    )

    private val MEDIA_FILE = Regex("""\.(?:m3u8|mpd|mp4|webm|m4s|m4a|ts|vtt|srt|key)(?:[?#]|\z)""", RegexOption.IGNORE_CASE)

    fun isAdHost(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        if (host.isEmpty()) return false
        return AD_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** True when a sub-request should be dropped: an ad host that does not serve media. */
    fun shouldBlockSubRequest(url: String, accept: String?): Boolean {
        if (accept != null && (accept.contains("video/", true) || accept.contains("audio/", true))) return false
        if (MEDIA_FILE.containsMatchIn(url.lowercase())) return false
        return isAdHost(url)
    }
}

/** Saves the choice of the user: the focus state the page reports, and the ad guard. */
private class EmbeddedBrowserClient(
    private val onFocusChanged: (Boolean) -> Unit
) : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        CookieManager.getInstance().flush()
        // Focus on the player automatically. The result tells the screen whether it worked.
        view?.evaluateJavascript(VideoFocus.SCRIPT) { result -> onFocusChanged(result == "true") }
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val req = request ?: return null
        if (req.isForMainFrame) return null
        val url = req.url?.toString() ?: return null
        val accept = req.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value
        return if (EmbeddedBrowserGuard.shouldBlockSubRequest(url, accept)) {
            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        } else {
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val req = request ?: return false
        // Stops the page from navigating to an ad host as its main page.
        return req.isForMainFrame && EmbeddedBrowserGuard.isAdHost(req.url?.toString().orEmpty())
    }
}

/**
 * The page opened in an embedded browser, only when the user asks for it. A slim bar above the page
 * holds the back button and the focus toggle, so no page content is covered.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun EmbeddedBrowserPage(pageUrl: String, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var focused by remember(pageUrl) { mutableStateOf(false) }

    val webView = remember(pageUrl) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = EmbeddedBrowserClient(onFocusChanged = { focused = it })
            loadUrl(pageUrl)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // The bar is above the page, not over it, so the site's content and the player are never hidden.
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color(0xFF111111))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
            }
            Text(
                title,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            TextButton(onClick = {
                if (focused) {
                    webView.evaluateJavascript(VideoFocus.RESET) { focused = false }
                } else {
                    webView.evaluateJavascript(VideoFocus.APPLY) { result ->
                        val ok = result == "true"
                        focused = ok
                        if (!ok) {
                            Toast.makeText(context, "لم نجد فيديو واضحاً على هذه الصفحة", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }) {
                Text(if (focused) "عرض الصفحة" else "تركيز الفيديو", color = Color(0xFFFFB300))
            }
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}
