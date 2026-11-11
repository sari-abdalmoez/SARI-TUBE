package com.sari.tube.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ToolsBg = Color(0xFF0E0E10)
private val ToolsSurface = Color(0xFF1A1A1F)
private val ToolsBrand = Color(0xFFFFC53D)
private val ToolsMuted = Color(0xFF9A9AA5)

private data class ToolCard(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

/** Main tools page. No API keys or YouTube account cards are included. */
@Composable
fun ToolsScreen(onOpenDownloadSettings: () -> Unit) {
    val ctx = LocalContext.current
    var showAbout by remember { mutableStateOf(false) }

    val cards = listOf(
        ToolCard("المظهر", ThemeMode.label(), Icons.Filled.Settings) { ThemeMode.next(ctx) },
        ToolCard(
            "إعدادات التنزيل",
            "عدد المهام والسرعة والشبكة",
            Icons.Filled.Settings,
            onOpenDownloadSettings
        ),
        ToolCard(
            "مشاركة التطبيق",
            "أرسل SARI LOOP لأصدقائك",
            Icons.Filled.Share
        ) {
            shareApp(ctx)
        },
        ToolCard(
            "حول التطبيق",
            "الإصدار ومعلومات التطبيق",
            Icons.Filled.Info
        ) {
            showAbout = true
        }
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(ToolsBg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "الأدوات",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 20.dp)
        )

        cards.chunked(2).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                row.forEach { card ->
                    ToolTile(card, Modifier.weight(1f))
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("SARI LOOP") },
            text = {
                Text(
                    "الإصدار ${versionName(ctx)}\n" +
                        "تطبيق للبحث عن المحتوى وتنزيل الوسائط من الروابط المدعومة.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text("إغلاق")
                }
            }
        )
    }
}

@Composable
private fun ToolTile(card: ToolCard, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier
            .height(150.dp)
            .clip(shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .background(ToolsSurface)
            .clickable(onClick = card.onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End
    ) {
        Icon(
            imageVector = card.icon,
            contentDescription = card.title,
            tint = ToolsBrand,
            modifier = Modifier.size(32.dp)
        )

        Column(horizontalAlignment = Alignment.End) {
            Text(
                card.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End
            )
            Spacer(Modifier.height(4.dp))
            Text(
                card.subtitle,
                color = ToolsMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.End,
                maxLines = 2
            )
        }
    }
}

private fun shareApp(ctx: Context) {
    val sendIntent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(
            Intent.EXTRA_TEXT,
            "حمّل SARI LOOP: تطبيق للبحث عن المحتوى وتنزيل الوسائط من الروابط المدعومة."
        )

    ctx.startActivity(
        Intent.createChooser(sendIntent, "مشاركة SARI LOOP")
    )
}

private fun versionName(ctx: Context): String =
    runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"
