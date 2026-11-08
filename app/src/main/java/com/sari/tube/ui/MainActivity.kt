package com.sari.tube.ui

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.animation.core.animateFloatAsState

import kotlinx.coroutines.delay

import com.sari.tube.engine.MediaFormats

import androidx.lifecycle.LifecycleEventObserver

import androidx.lifecycle.Lifecycle

import androidx.compose.ui.platform.LocalLifecycleOwner

import androidx.compose.runtime.DisposableEffect

import androidx.activity.compose.BackHandler

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sari.tube.engine.AppPrefs
import com.sari.tube.engine.DoodResolver
import com.sari.tube.engine.UrlParser
import com.sari.tube.service.DownloadService
import com.sari.tube.ui.search.SearchScreen
import com.sari.tube.ui.search.SearchViewModel

private const val TAB_TOOLS = 0
private const val TAB_PLAY = 1
private const val TAB_DOWNLOAD = 2
private const val TOOLS_GRID = 0
private const val TOOLS_DOWNLOAD = 1

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.applyRate(this)
        setContent {
            SariTheme {
                var tab by rememberSaveable { mutableStateOf(TAB_DOWNLOAD) }
                var searching by rememberSaveable { mutableStateOf(false) }
                var pendingQuery by rememberSaveable { mutableStateOf("") }
                var sheetUrl by rememberSaveable { mutableStateOf<String?>(null) }
                var toolsPage by rememberSaveable { mutableStateOf(TOOLS_GRID) }

                // Back goes one step: search -> home, tools subpage -> tools grid, other tabs -> home.
                BackHandler(enabled = searching || tab != TAB_DOWNLOAD || toolsPage != TOOLS_GRID) {
                    when {
                        searching -> { searching = false }
                        tab == TAB_TOOLS && toolsPage != TOOLS_GRID -> { toolsPage = TOOLS_GRID }
                        else -> { tab = TAB_DOWNLOAD }
                    }
                }

                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            searching -> {
                                val vm = viewModel<SearchViewModel>()
                                LaunchedEffect(pendingQuery) {
                                    if (pendingQuery.isNotBlank()) {
                                        vm.startSearch(pendingQuery)
                                        pendingQuery = ""
                                    }
                                }
                                SearchScreen(
                                    vm = vm,
                                    onBack = { searching = false },
                                    onDownload = { url, sel, aud -> startDownload(url, sel, aud) }
                                )
                            }
                            tab == TAB_TOOLS -> when (toolsPage) {
                                TOOLS_DOWNLOAD -> SettingsScreen(
                                    onBack = { toolsPage = TOOLS_GRID }
                                )
                                else -> ToolsScreen(
                                    onOpenDownloadSettings = {
                                        toolsPage = TOOLS_DOWNLOAD
                                    }
                                )
                            }
                            tab == TAB_PLAY -> DownloadsScreen(onBack = { tab = TAB_DOWNLOAD })
                            else -> HomeScreen(
                                onSearch = { q ->
                                    pendingQuery = q
                                    searching = true
                                },
                                onDownload = { url -> sheetUrl = url }
                            )
                        }

                        sheetUrl?.let { u ->
                            FormatSheet(
                                url = u,
                                onDismiss = { sheetUrl = null },
                                onConfirm = { o ->
                                    startDownload(u, o.selector, o.isAudio)
                                    sheetUrl = null
                                }
                            )
                        }
                    }

                    if (!searching) {
                        BottomNav(selected = tab, onSelect = { tab = it })
                    }
                }
            }
        }
    }

    private fun startDownload(url: String, selector: String = "bv*+ba/b", audio: Boolean = false) {
        if (!AppPrefs.allowMobileData(this) &&
            !AppPrefs.isOnWifiOrEthernet(this)
        ) {
            Toast.makeText(
                this,
                "التنزيل مقيّد على Wi-Fi حسب الإعدادات",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        DownloadService.start(this, url, selector, audio)
        Toast.makeText(this, "بدأ التحميل", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SariTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFFC53D),
            onPrimary = Color(0xFF141414),
            background = Color(0xFF0E0E10),
            surface = Color(0xFF1A1A1F),
            onBackground = Color(0xFFEDEDED),
            onSurface = Color(0xFFEDEDED),
            onSurfaceVariant = Color(0xFF9A9AA5),
            outline = Color(0xFF2E2E36)
        ),
        content = content
    )
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        NavItem("الأدوات", ToolsGrid, selected == TAB_TOOLS) { onSelect(TAB_TOOLS) }
        NavItem("تشغيل", PlayLibrary, selected == TAB_PLAY) { onSelect(TAB_PLAY) }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(targetValue = if (active) 1.14f else 1f, label = "navScale")
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = tint,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun HomeScreen(
    onSearch: (String) -> Unit,
    onDownload: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val trimmed = text.trim()
    val isLink = UrlParser.firstHttpUrl(trimmed) != null ||
        DoodResolver.extractId(trimmed) != null

    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var copiedLink by remember { mutableStateOf<String?>(null) }
    var dismissedLink by rememberSaveable { mutableStateOf<String?>(null) }

    // Reads the clipboard when the app opens and whenever it returns to the foreground.
    DisposableEffect(lifecycleOwner) {
        fun readClipboard() {
            val clip = clipboard.getText()?.text?.trim().orEmpty()
            copiedLink = clip.takeIf { UrlParser.firstHttpUrl(it) != null }
        }
        readClipboard()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) readClipboard()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Formats are read in the background once a link is complete, so the format sheet opens faster.
    LaunchedEffect(trimmed) {
        if (isLink) {
            delay(600)
            runCatching { MediaFormats.fetch(trimmed) }
        }
    }

    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        FloatingDecor(Modifier.fillMaxSize())
        HomeHeader(
            value = text,
            onValueChange = { text = it },
            onSearch = {
                if (trimmed.isNotEmpty()) {
                    onSearch(trimmed)
                    text = ""
                }
            },
            onDownloadLink = {
                if (trimmed.isNotEmpty() && isLink) {
                    onDownload(trimmed)
                    text = ""
                }
            },
            modifier = Modifier.fillMaxWidth().offset(y = (-28).dp)
        )

        val link = copiedLink
        if (link != null && link != dismissedLink && link != trimmed) {
            QuickLinkCard(
                url = link,
                onDownload = { onDownload(link) },
                onDismiss = { dismissedLink = link },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 120.dp)
            )
        }
    }
}
