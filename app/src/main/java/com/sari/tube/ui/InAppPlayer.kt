package com.sari.tube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sari.tube.engine.PlaybackResolver
import com.sari.tube.ui.player.VideoPlayer

/**
 * Plays a file saved on the device (downloads and local videos) with the same controls as the web player.
 * The player is created when the file opens and released when it closes, so nothing plays in the background.
 */
@Composable
fun InAppPlayer(uri: String, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember(uri) { mutableIntStateOf(0) }
    var player by remember(uri) { mutableStateOf<ExoPlayer?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }

    DisposableEffect(uri, attempt) {
        error = null
        val p = PlaybackResolver.createLocalPlayer(context, uri)
        player = p
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = PlaybackResolver.describe(e)
            }
        }
        p.addListener(listener)
        onDispose {
            p.removeListener(listener)
            p.release()
            player = null
        }
    }

    BackHandler(onBack = onClose)

    val current = player
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (current != null) {
            VideoPlayer(
                player = current,
                title = title,
                onBack = onClose,
                modifier = Modifier.fillMaxSize(),
                errorMessage = error,
                onRetry = { attempt++ }
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFFB300))
            }
        }
    }
}
