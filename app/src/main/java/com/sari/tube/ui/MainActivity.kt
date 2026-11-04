package com.sari.tube.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sari.tube.engine.AppPrefs
import com.sari.tube.engine.DoodResolver
import com.sari.tube.engine.UrlParser
import com.sari.tube.service.DownloadService
import com.sari.tube.ui.search.SearchScreen
import com.sari.tube.ui.search.SearchViewModel

private const val SCREEN_HOME = 0
private const val SCREEN_DOWNLOADS = 1
private const val SCREEN_SETTINGS = 2
private const val SCREEN_SEARCH = 3

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.applyRate(this)
        setContent {
            SariTheme {
                var screen by rememberSaveable { mutableStateOf(SCREEN_HOME) }
                when (screen) {
                    SCREEN_DOWNLOADS -> DownloadsScreen(onBack = { screen = SCREEN_HOME })
                    SCREEN_SETTINGS -> SettingsScreen(onBack = { screen = SCREEN_HOME })
                    SCREEN_SEARCH -> SearchScreen(
                        vm = viewModel<SearchViewModel>(),
                        onBack = { screen = SCREEN_HOME },
                        onDownload = { item -> startDownload(item.url) }
                    )
                    else -> HomeScreen(
                        onOpenDownloads = { screen = SCREEN_DOWNLOADS },
                        onOpenSettings = { screen = SCREEN_SETTINGS },
                        onSearch = { screen = SCREEN_SEARCH },
                        onDownload = { url -> startDownload(url) }
                    )
                }
            }
        }
    }

    private fun startDownload(url: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        DownloadService.start(this, url)
        Toast.makeText(this, "بدأ التحميل — تابع الإشعارات", Toast.LENGTH_SHORT).show()
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
fun HomeScreen(
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onSearch: () -> Unit,
    onDownload: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val valid = UrlParser.firstHttpUrl(text) != null || DoodResolver.extractId(text) != null

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().offset(y = (-72).dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "SARI TUBE",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "حمّل فيديوهاتك بسهولة",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(40.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("الصق رابط الفيديو هنا") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = {
                        TextButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                            Text("لصق", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    onDownload(text.trim())
                    text = ""
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("تحميل", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onSearch,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("بحث عن فيديو أو أغنية")
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "يظهر تقدم التحميل في الإشعارات.\nيُحفظ الفيديو في: Download/SARI TUBE",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // شريط الأعلى يُرسم آخراً ليستقبل النقرات. المواضع ثابتة LTR: القائمة يساراً والإعدادات يميناً.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenDownloads) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = "الملفات المنزّلة",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "الإعدادات",
                        tint = Color(0xFF9A9AA5),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
