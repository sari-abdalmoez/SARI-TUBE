package com.sari.tube.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sari.tube.engine.DoodResolver
import com.sari.tube.engine.UrlParser
import com.sari.tube.service.DownloadService

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SariTheme {
                HomeScreen(onDownload = { url ->
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    DownloadService.start(this, url)
                })
            }
        }
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
fun HomeScreen(onDownload: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val valid = UrlParser.firstHttpUrl(text) != null || DoodResolver.extractId(text) != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                        ?.let { text = it }
                }) {
                    Text("لصق", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
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

        Spacer(Modifier.height(24.dp))
        Text(
            "يظهر تقدم التحميل في الإشعارات.\nيُحفظ الفيديو في: Download/SARI TUBE",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
