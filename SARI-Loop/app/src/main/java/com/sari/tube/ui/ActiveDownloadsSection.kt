package com.sari.tube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sari.tube.engine.ActiveDownload
import com.sari.tube.engine.ActiveDownloads
import com.sari.tube.engine.DlState
import com.sari.tube.service.DownloadService
import java.util.Locale

/** In-app list of downloads in progress. It reads the same state the notifications are built from. */
@Composable
fun ActiveDownloadsSection() {
    val items by ActiveDownloads.items.collectAsState()
    if (items.isEmpty()) return
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "قيد التحميل",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        items.values.sortedBy { it.id }.forEach { d ->
            ActiveRow(
                d = d,
                onCancel = { DownloadService.cancel(ctx, d.id) },
                onDismiss = { ActiveDownloads.remove(d.id) }
            )
        }
    }
}

@Composable
private fun ActiveRow(d: ActiveDownload, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val failed = d.state == DlState.FAILED
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                d.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = if (failed) onDismiss else onCancel) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = if (failed) "إزالة" else "إلغاء",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        if (failed) {
            Text(d.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        } else {
            if (d.percent >= 0) {
                LinearProgressIndicator(
                    progress = { d.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                // Total unknown: show activity without inventing a percentage.
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(details(d), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

/** Shows only values the engine actually reported. Missing values are left out, never guessed. */
private fun details(d: ActiveDownload): String {
    if (d.state == DlState.QUEUED) return "في قائمة الانتظار"
    val parts = mutableListOf<String>()
    if (d.percent >= 0) parts += "${d.percent}%"
    if (d.downloadedBytes > 0) {
        parts += if (d.totalBytes > 0) "${size(d.downloadedBytes)} / ${size(d.totalBytes)}"
        else size(d.downloadedBytes)
    }
    if (d.speedBps > 0) parts += "${size(d.speedBps)}/s"
    if (d.etaSec > 0) parts += "متبقٍ ${d.etaSec} ث"
    return if (parts.isEmpty()) d.message else parts.joinToString("  •  ")
}

private fun size(b: Long): String = when {
    b >= 1048576L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1024L -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}
