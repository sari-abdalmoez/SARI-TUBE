package com.sari.tube.ui.search

import androidx.media3.ui.PlayerView

import androidx.media3.exoplayer.ExoPlayer

import androidx.media3.common.MediaItem

import androidx.compose.ui.viewinterop.AndroidView

import androidx.compose.ui.platform.LocalContext

import androidx.compose.runtime.rememberSaveable

import androidx.compose.runtime.DisposableEffect

import androidx.activity.result.contract.ActivityResultContracts

import androidx.activity.compose.rememberLauncherForActivityResult

import android.content.Intent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sari.tube.engine.VideoItem
import com.sari.tube.ui.DownloadArrow
import com.sari.tube.ui.FormatSheet
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onBack: () -> Unit,
    onDownload: (url: String, selector: String, audioOnly: Boolean) -> Unit
) {
    val tabs = SearchTab.entries
    val selectedIndex = tabs.indexOf(vm.tab)
    var pick by remember { mutableStateOf<VideoItem?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع", tint = MaterialTheme.colorScheme.onBackground)
            }
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),

                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit() })
            )
        }

        ScrollableTabRow(
            selectedTabIndex = selectedIndex,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = i == selectedIndex,
                    onClick = { vm.onTabSelected(t) },
                    text = { Text(t.label, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        if (vm.tab == SearchTab.LOCAL) {
            LocalVideoPanel()
        } else when (val s = vm.state) {
            SearchUiState.Idle -> Spacer(Modifier.fillMaxSize())

            SearchUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            is SearchUiState.Failed -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.retry() }) { Text("إعادة المحاولة") }
            }

            is SearchUiState.Ready -> {
                val tab = vm.tab
                if (!tab.searchable) {
                    CenterMessage(
                        "البحث المباشر في ${tab.label} غير متاح. ألصق رابط الفيديو في الشاشة الرئيسية للتحميل."
                    )
                } else {
                    val visible = when (tab) {
                        SearchTab.SONGS -> s.items.filter { SearchViewModel.isSong(it) }
                        SearchTab.VIDEOS -> s.items.filterNot { SearchViewModel.isSong(it) }
                        else -> s.items
                    }
                    if (visible.isEmpty()) {
                        CenterMessage("لا توجد نتائج في هذا التبويب")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(visible, key = { it.id }) { item ->
                                VideoCard(item = item, onDownload = { pick = item })
                            }
                        }
                    }
                }
            }
        }
    }
    pick?.let { p ->
        FormatSheet(
            url = p.url,
            onDismiss = { pick = null },
            onConfirm = { o ->
                onDownload(p.url, o.selector, o.isAudio)
                pick = null
            }
        )
    }
}

@Composable
private fun VideoCard(item: VideoItem, onDownload: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(128.dp, 72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.outline)
        ) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (item.durationSec > 0) {
                Text(
                    formatDuration(item.durationSec),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                item.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                item.channel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            item.views?.let {
                Text(
                    "${formatViews(it)} مشاهدة",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        IconButton(onClick = onDownload) {
            Icon(DownloadArrow, contentDescription = "تنزيل", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatViews(v: Long): String = when {
    v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
    v >= 1_000 -> String.format(Locale.US, "%.0fK", v / 1_000.0)
    else -> v.toString()
}


@Composable
private fun LocalVideoPanel() {
    val context = LocalContext.current
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            selectedUri = uri.toString()
        }
    }

    val player = remember(selectedUri) {
        selectedUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = { picker.launch(arrayOf("video/*")) }) {
            Text("اختيار فيديو من الهاتف")
        }

        Spacer(Modifier.height(12.dp))

        if (player != null) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = true
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    " ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
