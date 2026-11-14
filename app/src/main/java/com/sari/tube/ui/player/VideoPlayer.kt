package com.sari.tube.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

private val Accent = Color(0xFFFFB300)
private val Scrim = Color(0x66000000)
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val CONTROLS_HIDE_MS = 3_500L

/**
 * Observable state of one player. Compose reads these fields directly, so the UI updates
 * as soon as the player changes.
 */
@Stable
private class PlayerUi(val player: ExoPlayer) {
    var isPlaying by mutableStateOf(player.isPlaying)
    var buffering by mutableStateOf(player.playbackState == Player.STATE_BUFFERING)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var tracks by mutableStateOf(player.currentTracks)
    var speed by mutableFloatStateOf(player.playbackParameters.speed)
    var volume by mutableFloatStateOf(player.volume)
    var brightness by mutableFloatStateOf(-1f) // -1 means the system brightness
    var controlsVisible by mutableStateOf(true)
    var tick by mutableLongStateOf(0L)
    var dragging by mutableStateOf(false)
    var dragValue by mutableFloatStateOf(0f)

    fun touch() {
        controlsVisible = true
        tick++
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            player.play()
        }
    }

    fun setSpeed(value: Float) {
        player.setPlaybackSpeed(value)
        speed = value
    }

    fun setVolume(value: Float) {
        player.volume = value
        volume = value
    }
}

/** One row in the subtitle or audio list. */
private class TrackOption(val label: String, val selected: Boolean, val apply: (ExoPlayer) -> Unit)

/**
 * Video player with its own controls: play/pause, 10-second jumps, a seek bar, speed, quality,
 * subtitles and audio tracks (when the source provides them), volume, brightness, and fullscreen.
 *
 * Controls hide after a few seconds of playback and reappear on tap. [errorMessage] replaces the
 * picture with a readable message and a retry button.
 */
@Composable
fun VideoPlayer(
    player: ExoPlayer,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    qualityLabels: List<String> = emptyList(),
    selectedQuality: Int = 0,
    onQualitySelected: (Int) -> Unit = {},
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null
) {
    val ui = remember(player) { PlayerUi(player) }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    // Listens for changes the UI must show.
    DisposableEffect(ui) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui.isPlaying = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                ui.buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onTracksChanged(tracks: Tracks) {
                ui.tracks = tracks
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                ui.speed = playbackParameters.speed
            }
        }
        ui.player.addListener(listener)
        onDispose { ui.player.removeListener(listener) }
    }

    // Position and duration are polled, because Player does not report them continuously.
    LaunchedEffect(ui) {
        while (isActive) {
            ui.position = ui.player.currentPosition.coerceAtLeast(0L)
            val d = ui.player.duration
            ui.duration = if (d > 0) d else 0L
            delay(250L)
        }
    }

    // Controls hide while playing and stay visible when paused, on error, or after a tap.
    LaunchedEffect(ui.tick, ui.isPlaying, ui.dragging) {
        ui.controlsVisible = true
        if (ui.isPlaying && !ui.dragging) {
            delay(CONTROLS_HIDE_MS)
            ui.controlsVisible = false
        }
    }

    // The player pauses when the app goes to the background, so audio does not keep playing.
    DisposableEffect(lifecycleOwner, ui) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) ui.player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Restores the system brightness when the player leaves the screen.
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.also { it.screenBrightness = -1f }
            }
        }
    }

    Box(modifier.background(Color.Black)) {
        if (fullscreen) {
            // The fullscreen dialog owns the surface while it is open. Showing two PlayerViews at once
            // would take the video output away from one of them.
            Box(Modifier.fillMaxSize().background(Color.Black))
        } else {
            PlayerSurface(
                ui = ui,
                title = title,
                isFullscreen = false,
                onBack = onBack,
                onToggleFullscreen = { fullscreen = true },
                qualityLabels = qualityLabels,
                selectedQuality = selectedQuality,
                onQualitySelected = onQualitySelected,
                errorMessage = errorMessage,
                onRetry = onRetry
            )
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val view = LocalView.current
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window
            FullscreenEffects(activity, dialogWindow)
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PlayerSurface(
                    ui = ui,
                    title = title,
                    isFullscreen = true,
                    onBack = { fullscreen = false },
                    onToggleFullscreen = { fullscreen = false },
                    qualityLabels = qualityLabels,
                    selectedQuality = selectedQuality,
                    onQualitySelected = onQualitySelected,
                    errorMessage = errorMessage,
                    onRetry = onRetry
                )
            }
        }
    }
}

@Composable
private fun PlayerSurface(
    ui: PlayerUi,
    title: String,
    isFullscreen: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    qualityLabels: List<String>,
    selectedQuality: Int,
    onQualitySelected: (Int) -> Unit,
    errorMessage: String?,
    onRetry: (() -> Unit)?
) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    // In a dialog the window is the dialog's own; inline it is the activity's window.
    val window: Window? = (view.parent as? DialogWindowProvider)?.window ?: activity?.window
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(window, ui.brightness) {
        window?.let { w ->
            w.attributes = w.attributes.also { it.screenBrightness = ui.brightness }
        }
    }

    val showControls = ui.controlsVisible || errorMessage != null

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = ui.player
                }
            },
            update = { v ->
                if (v.player !== ui.player) v.player = ui.player
                v.keepScreenOn = ui.isPlaying
            },
            modifier = Modifier.fillMaxSize()
        )

        // Transparent layer that catches taps on the picture. It sits above the video and below the controls.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (ui.controlsVisible) ui.controlsVisible = false else ui.touch()
                }
        )

        if (ui.buffering && errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(48.dp),
                color = Accent,
                strokeWidth = 3.dp
            )
        }

        if (showControls) {
            Box(Modifier.fillMaxSize().background(if (errorMessage == null) Scrim else Color.Black.copy(alpha = 0.85f))) {
                // Top bar: back, title, settings and fullscreen.
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                    Text(
                        title,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = {
                        showSettings = true
                        ui.touch()
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "الإعدادات", tint = Color.White)
                    }
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(
                            if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = if (isFullscreen) "الخروج من ملء الشاشة" else "ملء الشاشة",
                            tint = Color.White
                        )
                    }
                }

                if (errorMessage != null) {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            errorMessage,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            fontSize = 15.sp
                        )
                        if (onRetry != null) {
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onRetry) { Text("إعادة المحاولة") }
                        }
                    }
                } else {
                    // Center: 10-second jumps and play/pause.
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        IconButton(
                            onClick = {
                                ui.player.seekBack()
                                ui.touch()
                            },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                Icons.Filled.Replay10,
                                contentDescription = "تأخير 10 ثوانٍ",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                ui.togglePlay()
                                ui.touch()
                            },
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (ui.isPlaying) "إيقاف مؤقت" else "تشغيل",
                                tint = Color.White,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                ui.player.seekForward()
                                ui.touch()
                            },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                Icons.Filled.Forward10,
                                contentDescription = "تقديم 10 ثوانٍ",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    // Bottom: seek bar and times.
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        val maxValue = ui.duration.coerceAtLeast(1L).toFloat()
                        val shown = if (ui.dragging) ui.dragValue else ui.position.toFloat()
                        Slider(
                            value = shown.coerceIn(0f, maxValue),
                            onValueChange = {
                                ui.dragging = true
                                ui.dragValue = it
                            },
                            onValueChangeFinished = {
                                ui.player.seekTo(ui.dragValue.toLong())
                                ui.dragging = false
                                ui.touch()
                            },
                            valueRange = 0f..maxValue,
                            enabled = ui.duration > 0,
                            colors = SliderDefaults.colors(
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(shown.toLong()), color = Color.White, fontSize = 12.sp)
                            Text(
                                if (ui.duration > 0) formatTime(ui.duration) else "--:--",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            ui = ui,
            qualityLabels = qualityLabels,
            selectedQuality = selectedQuality,
            onQualitySelected = onQualitySelected,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun SettingsDialog(
    ui: PlayerUi,
    qualityLabels: List<String>,
    selectedQuality: Int,
    onQualitySelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val tracks = ui.tracks
    val (subtitles, audio) = remember(tracks, ui.player) { buildTrackOptions(ui.player, tracks) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C1C1E),
            contentColor = Color.White
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (qualityLabels.size > 1) {
                    SectionTitle("الجودة")
                    qualityLabels.forEachIndexed { index, label ->
                        OptionRow(label, selected = index == selectedQuality) {
                            onQualitySelected(index)
                            onDismiss()
                        }
                    }
                }

                SectionTitle("السرعة")
                SPEEDS.forEach { s ->
                    OptionRow(
                        label = if (s % 1f == 0f) "${s.toInt()}x" else "${s}x",
                        selected = kotlin.math.abs(ui.speed - s) < 0.01f
                    ) { ui.setSpeed(s) }
                }

                if (subtitles.isNotEmpty()) {
                    SectionTitle("الترجمة")
                    subtitles.forEach { opt ->
                        OptionRow(opt.label, opt.selected) {
                            opt.apply(ui.player)
                            ui.touch()
                        }
                    }
                }

                if (audio.size > 1) {
                    SectionTitle("مسار الصوت")
                    audio.forEach { opt ->
                        OptionRow(opt.label, opt.selected) {
                            opt.apply(ui.player)
                            ui.touch()
                        }
                    }
                }

                SectionTitle("مستوى الصوت")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = Color.White)
                    Slider(
                        value = ui.volume,
                        onValueChange = { ui.setVolume(it) },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                    )
                }

                SectionTitle("السطوع")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Brightness6, contentDescription = null, tint = Color.White)
                    Slider(
                        value = (if (ui.brightness < 0f) 0.5f else ui.brightness).coerceIn(0.01f, 1f),
                        onValueChange = { ui.brightness = it.coerceAtLeast(0.01f) },
                        valueRange = 0.01f..1f,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        color = Color(0xFF9E9E9E),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (selected) Accent else Color.White,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Accent)
    }
}

/** Hides the system bars and locks landscape while the player is fullscreen. Undone when it closes. */
@Composable
private fun FullscreenEffects(activity: Activity?, dialogWindow: Window?) {
    DisposableEffect(activity, dialogWindow) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        dialogWindow?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            dialogWindow?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/** Builds the subtitle and audio lists from the tracks the source provides. Empty when there are none. */
private fun buildTrackOptions(player: ExoPlayer, tracks: Tracks): Pair<List<TrackOption>, List<TrackOption>> {
    val subtitles = mutableListOf<TrackOption>()
    val audio = mutableListOf<TrackOption>()

    for (group in tracks.groups) {
        val type = group.type
        if (type != C.TRACK_TYPE_TEXT && type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            val name = format.label
                ?: format.language?.let { Locale(it).getDisplayLanguage(Locale("ar")) }?.takeIf { it.isNotBlank() }
                ?: "مسار ${i + 1}"
            val option = TrackOption(
                label = name,
                selected = group.isTrackSelected(i),
                apply = { p ->
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .setTrackTypeDisabled(type, false)
                        .build()
                }
            )
            if (type == C.TRACK_TYPE_TEXT) subtitles += option else audio += option
        }
    }

    if (subtitles.isNotEmpty()) {
        val none = subtitles.none { it.selected }
        subtitles.add(
            0,
            TrackOption(
                label = "بدون ترجمة",
                selected = none,
                apply = { p ->
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
            )
        )
    }
    return subtitles to audio
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
