package com.sari.tube.ui.player

import androidx.compose.foundation.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sari.tube.engine.PlaybackResolver
import com.sari.tube.engine.PlaybackSession
import kotlinx.coroutines.CancellationException

/**
 * Plays a web page (for example a YouTube result). It extracts the media URL, plays it, and handles
 * the common failures:
 * - a link that expired (the server returns 403): extracted again once, keeping the position;
 * - a quality change: the player is rebuilt at the same position;
 * - a network or extraction error: a readable message with a retry button.
 */
@Composable
fun StreamPlayer(
    pageUrl: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var retryNonce by remember(pageUrl) { mutableIntStateOf(0) }
    var autoRefreshed by remember(pageUrl) { mutableIntStateOf(0) }
    var session by remember(pageUrl) { mutableStateOf<PlaybackSession?>(null) }
    var selected by remember(pageUrl) { mutableIntStateOf(-1) }
    var resolving by remember(pageUrl) { mutableStateOf(true) }
    var resolveError by remember(pageUrl) { mutableStateOf<String?>(null) }
    var playerError by remember(pageUrl) { mutableStateOf<String?>(null) }
    var player by remember(pageUrl) { mutableStateOf<ExoPlayer?>(null) }
    var resumeMs by remember(pageUrl) { mutableLongStateOf(0L) }

    // Extracts the qualities. Runs again on retry, and when the link has expired.
    LaunchedEffect(pageUrl, retryNonce) {
        resolving = true
        resolveError = null
        playerError = null
        session = null
        try {
            val s = PlaybackResolver.resolve(context, pageUrl)
            session = s
            if (selected !in s.streams.indices) selected = s.defaultIndex
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            resolveError = e.message?.takeIf { it.isNotBlank() } ?: "تعذر تشغيل الفيديو."
        } finally {
            resolving = false
        }
    }

    val stream = session?.streams?.getOrNull(selected)

    // Creates the player for the chosen quality. Releases it when the quality changes or the screen closes.
    DisposableEffect(stream) {
        if (stream == null) {
            player = null
            return@DisposableEffect onDispose { }
        }
        val p = PlaybackResolver.createPlayer(context, stream, resumeMs)
        player = p
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (PlaybackResolver.isExpiredLink(error) && autoRefreshed < 1) {
                    // The link probably expired. Extract it again once, from the same position.
                    autoRefreshed++
                    resumeMs = p.currentPosition.coerceAtLeast(0L)
                    retryNonce++
                } else {
                    playerError = PlaybackResolver.describe(error)
                }
            }
        }
        p.addListener(listener)
        onDispose {
            resumeMs = p.currentPosition.coerceAtLeast(0L)
            p.removeListener(listener)
            p.release()
            player = null
        }
    }

    val current: ExoPlayer? = player
    val qualities = session?.streams?.map { it.label }.orEmpty()

    Box(modifier.fillMaxSize().background(Color.Black)) {
        when {
            resolving -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFFB300))
            }

            resolveError != null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    resolveError.orEmpty(),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    autoRefreshed = 0
                    retryNonce++
                }) { Text("إعادة المحاولة") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onBack) { Text("رجوع") }
            }

            current != null -> VideoPlayer(
                player = current,
                title = title,
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
                qualityLabels = qualities,
                selectedQuality = selected.coerceAtLeast(0),
                onQualitySelected = { index ->
                    if (index != selected) selected = index
                },
                errorMessage = playerError,
                onRetry = {
                    autoRefreshed = 0
                    retryNonce++
                }
            )

            else -> Box(Modifier.fillMaxSize())
        }
    }
}
