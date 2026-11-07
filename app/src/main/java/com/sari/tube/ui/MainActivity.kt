package com.sari.tube.ui

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
                            tab == TAB_TOOLS -> SettingsScreen(onBack = { tab = TAB_DOWNLOAD })
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
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        NavItem("الأدوات", ToolsGrid, selected == TAB_TOOLS) { onSelect(TAB_TOOLS) }
        NavItem("تشغيل", PlayLibrary, selected == TAB_PLAY) { onSelect(TAB_PLAY) }
        NavItem("تنزيل", DownloadArrow, selected == TAB_DOWNLOAD) { onSelect(TAB_DOWNLOAD) }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
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
    val clipboard = LocalClipboardManager.current
    val trimmed = text.trim()
    val isLink = UrlParser.firstHttpUrl(trimmed) != null || DoodResolver.extractId(trimmed) != null

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().offset(y = (-48).dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val brandTransition = rememberInfiniteTransition(label = "sari-loop-brand")
                val brandColor by brandTransition.animateColor(
                    initialValue = Color(0xFFFFD54F),
                    targetValue = Color(0xFFFF6D00),
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 7000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "sari-loop-color"
                )

                Text(
                    "SARI LOOP",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = brandColor,
                    letterSpacing = 1.8.sp
                )
                Spacer(Modifier.height(36.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),

                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = {
                        androidx.compose.material3.TextButton(onClick = {
                            clipboard.getText()?.text?.let { text = it }
                        }) {
                            Text("لصق", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
            Spacer(Modifier.height(16.dp))

            // Gradient yellow-to-orange action button. Its label and icon follow the input.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .alpha(if (trimmed.isNotEmpty()) 1f else 0.4f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFFC53D), Color(0xFFFF8A00))))
                    .clickable(enabled = trimmed.isNotEmpty()) {
                        if (isLink) onDownload(trimmed) else onSearch(trimmed)
                        text = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isLink) DownloadArrow else Icons.Filled.Search,
                        contentDescription = null,
                        tint = Color(0xFF141414)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isLink) "تنزيل" else "بحث",
                        color = Color(0xFF141414),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "يظهر تقدم التحميل في الإشعارات.\nيُحفظ الفيديو في: Download/SARI LOOP",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }


    }
}
