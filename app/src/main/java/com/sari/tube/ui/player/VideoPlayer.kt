package com.sari.tube.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Accent = Color(0xFFFFB300)
private val Dim = Color(0x66000000)
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val CONTROLS_HIDE_MS = 3_500L

/** Observable state of one player. Compose reads these fields directly and updates as they change. */
@Stable
private class PlayerUi(val player: ExoPlayer, private val audio: AudioManager?) {
    var isPlaying by mutableStateOf(player.isPlaying)
    var buffering by mutableStateOf(player.playbackState == Player.STATE_BUFFERING)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var tracks by mutableStateOf(player.currentTracks)
    var speed by mutableFloatStateOf(player.playbackParameters.speed)
    var volume by mutableFloatStateOf(readDeviceVolume())
    var brightness by mutableFloatStateOf(-1f) // -1 means the system brightness
    var fill by mutableStateOf(true)           // true: crop the picture to fill the screen (the default)
    var locked by mutableStateOf(false)
    var controlsVisible by mutableStateOf(true)
    var tick by mutableLongStateOf(0L)
    var dragging by mutableStateOf(false)
    var dragValue by mutableFloatStateOf(0f)

    private fun readDeviceVolume(): Float {
        val a = audio ?: return 0.5f
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return a.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

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

    /** Changes the phone's music volume, which is the volume the user hears. */
    fun setVolume(value: Float) {
        val a = audio
        if (a != null) {
            val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            a.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt().coerceIn(0, max), 0)
        }
        volume = value
    }
}

/** One row in the subtitle or audio list. */
private class TrackOption(val label: String, val selected: Boolean, val apply: (ExoPlayer) -> Unit)

/**
 * Full-screen video player, in landscape, in the style of the reference design:
 * volume on the left, brightness and lock on the right, a thin progress bar, and the transport
 * controls at the bottom. Controls hide after a few seconds while playing and come back on tap.
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
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val ui = remember(player) { PlayerUi(player, audio) }
    val lifecycleOwner = LocalLifecycleOwner.current

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

    // Controls hide while playing and stay visible when paused, while dragging, or after a tap.
    LaunchedEffect(ui.tick, ui.isPlaying, ui.dragging) {
        ui.controlsVisible = true
        if (ui.isPlaying && !ui.dragging) {
            delay(CONTROLS_HIDE_MS)
            ui.controlsVisible = false
        }
    }

    // Pause when the app goes to the background, so audio does not keep playing unseen.
    DisposableEffect(lifecycleOwner, ui) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) ui.player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A dialog covers the whole screen, status bar included, so the player is truly full screen.
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        FullscreenEffects(activity, dialogWindow)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerSurface(
                ui = ui,
                title = title,
                onBack = onBack,
                qualityLabels = qualityLabels,
                selectedQuality = selectedQuality,
                onQualitySelected = onQualitySelected,
                errorMessage = errorMessage,
                onRetry = onRetry,
                window = dialogWindow
            )
        }
    }
}

@Composable
private fun PlayerSurface(
    ui: PlayerUi,
    title: String,
    onBack: () -> Unit,
    qualityLabels: List<String>,
    selectedQuality: Int,
    onQualitySelected: (Int) -> Unit,
    errorMessage: String?,
    onRetry: (() -> Unit)?,
    window: Window?
) {
    var showSettings by remember { mutableStateOf(false) }
    val resize = if (ui.fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    val keepOn = ui.isPlaying
    val clock = rememberClock()

    LaunchedEffect(window, ui.brightness) {
        window?.let { w ->
            w.attributes = w.attributes.also { it.screenBrightness = ui.brightness }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = ui.player
                }
            },
            update = { v ->
                if (v.player !== ui.player) v.player = ui.player
                v.resizeMode = resize
                v.keepScreenOn = keepOn
            },
            modifier = Modifier.fillMaxSize()
        )

        // Transparent layer that catches taps on the picture, under the controls.
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

        when {
            errorMessage != null -> ErrorOverlay(errorMessage, onBack, onRetry)

            ui.locked -> if (ui.controlsVisible) {
                // Locked: only the lock button stays, and tapping it unlocks.
                IconButton(
                    onClick = {
                        ui.locked = false
                        ui.touch()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = "إلغاء القفل", tint = Color.White)
                }
            }

            ui.controlsVisible -> Box(Modifier.fillMaxSize().background(Dim)) {
                TopBar(title, clock, ui, onBack, onOpenSettings = {
                    showSettings = true
                    ui.touch()
                })

                // Left: device volume.
                Column(
                    Modifier.align(Alignment.CenterStart).padding(start = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    VerticalSlider(
                        value = ui.volume,
                        onChange = {
                            ui.applyVolume(it)
                            ui.touch()
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    Icon(Icons.Filled.VolumeUp, contentDescription = "الصوت", tint = Color.White)
                }

                // Right: brightness, and the lock button below it.
                Row(
                    Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VerticalSlider(
                            value = if (ui.brightness < 0f) 0.5f else ui.brightness,
                            onChange = {
                                ui.brightness = it.coerceIn(0.01f, 1f)
                                ui.touch()
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                        Icon(Icons.Filled.Brightness6, contentDescription = "السطوع", tint = Color.White)
                    }
                    Spacer(Modifier.width(18.dp))
                    IconButton(onClick = {
                        ui.locked = true
                        ui.touch()
                    }) {
                        Icon(Icons.Filled.LockOpen, contentDescription = "قفل الشاشة", tint = Color.White)
                    }
                }

                BottomBar(ui, qualityLabels, selectedQuality, onOpenSettings = {
                    showSettings = true
                    ui.touch()
                })
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
private fun BoxScope.TopBar(
    title: String,
    clock: String,
    ui: PlayerUi,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 4.dp),
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
        Text(clock, color = Color.White, fontSize = 14.sp)
        IconButton(onClick = {
            ui.fill = !ui.fill
            ui.touch()
        }) {
            Icon(
                if (ui.fill) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (ui.fill) "إظهار كامل الصورة" else "ملء الشاشة",
                tint = Color.White
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.MoreHoriz, contentDescription = "الإعدادات", tint = Color.White)
        }
    }
}

@Composable
private fun BoxScope.BottomBar(
    ui: PlayerUi,
    qualityLabels: List<String>,
    selectedQuality: Int,
    onOpenSettings: () -> Unit
) {
    val maxValue = ui.duration.coerceAtLeast(1L).toFloat()
    val shown = if (ui.dragging) ui.dragValue else ui.position.toFloat()

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${formatTime(shown.toLong())} / ${if (ui.duration > 0) formatTime(ui.duration) else "--:--"}",
                color = Color.White,
                fontSize = 12.sp
            )
        }
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
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.35f)
            )
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: quality, subtitles and speed. Each one opens the settings list.
            Row(verticalAlignment = Alignment.CenterVertically) {
                qualityLabels.getOrNull(selectedQuality)?.let { label ->
                    Text(
                        label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenSettings)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.MoreHoriz, contentDescription = "الترجمة والصوت", tint = Color.White)
                }
                Text(
                    speedLabel(ui.speed),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 13.sp
                )
            }

            // Right: 10-second jumps and play/pause.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    ui.player.seekBack()
                    ui.touch()
                }) {
                    Icon(Icons.Filled.Replay10, contentDescription = "تأخير 10 ثوانٍ", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = {
                    ui.player.seekForward()
                    ui.touch()
                }) {
                    Icon(Icons.Filled.Forward10, contentDescription = "تقديم 10 ثوانٍ", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = {
                    ui.togglePlay()
                    ui.touch()
                }) {
                    Icon(
                        if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (ui.isPlaying) "إيقاف مؤقت" else "تشغيل",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onBack: () -> Unit, onRetry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f))) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(6.dp)
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
        }
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(message, color = Color.White, textAlign = TextAlign.Center, fontSize = 15.sp)
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("إعادة المحاولة") }
            }
        }
    }
}

/** A vertical slider drawn by hand: Material has no vertical slider. Works with taps and drags. */
@Composable
private fun VerticalSlider(value: Float, onChange: (Float) -> Unit) {
    val v = value.coerceIn(0f, 1f)
    Box(
        Modifier
            .width(32.dp)
            .height(170.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange((1f - offset.y / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    onChange((1f - change.position.y / size.height).coerceIn(0f, 1f))
                }
            }
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.35f))
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .width(3.dp)
                .fillMaxHeight(v)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White)
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
                    OptionRow(speedLabel(s), selected = kotlin.math.abs(ui.speed - s) < 0.01f) {
                        ui.setSpeed(s)
                    }
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

/** Hides the system bars and locks landscape while the player is open. Both are restored on close. */
@Composable
private fun FullscreenEffects(activity: Activity?, dialogWindow: Window?) {
    DisposableEffect(activity, dialogWindow) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        dialogWindow?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BY_SWIPE
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

/** Current time, refreshed every 10 seconds. */
@Composable
private fun rememberClock(): String {
    var now by remember { mutableStateOf(formatClock()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            now = formatClock()
            delay(10_000L)
        }
    }
    return now
}

private fun formatClock(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())

private fun speedLabel(s: Float): String = if (s % 1f == 0f) "${s.toInt()}x" else "${s}x"

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
        subtitles.add(
            0,
            TrackOption(
                label = "بدون ترجمة",
                selected = subtitles.none { it.selected },
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
