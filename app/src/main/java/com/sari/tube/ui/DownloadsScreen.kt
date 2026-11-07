package com.sari.tube.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sari.tube.engine.ActiveDownload
import com.sari.tube.engine.ActiveDownloads
import com.sari.tube.engine.DlState
import com.sari.tube.service.DownloadService
import com.sari.tube.storage.DownloadRecord
import com.sari.tube.storage.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val playBackground = Color(0xFF09090B)
private val playCard = Color(0xFF18181D)
private val playAccent = Color(0xFFFFC53D)
private val playMuted = Color(0xFF9999A3)

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    PlayScreen(onBack = onBack)
}

@Composable
fun PlayScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val activeMap by ActiveDownloads.items.collectAsState()
    val activeItems = remember(activeMap) {
        activeMap.values.sortedBy { it.id }
    }
    val activeIds = remember(activeItems) {
        activeItems.map { it.id }.toSet()
    }

    var records by remember {
        mutableStateOf(HistoryStore.all(context))
    }
    var searchVisible by rememberSaveable {
        mutableStateOf(false)
    }
    var query by rememberSaveable {
        mutableStateOf("")
    }

    // Refresh completed history only when the active download IDs change,
    // not on every progress update.
    LaunchedEffect(activeIds) {
        records = withContext(Dispatchers.IO) {
            HistoryStore.all(context)
        }
    }

    val filteredActive = remember(activeItems, query) {
        activeItems.filter {
            it.title.contains(query.trim(), ignoreCase = true)
        }
    }
    val filteredRecords = remember(records, query) {
        records.filter {
            it.name.contains(query.trim(), ignoreCase = true)
        }.sortedByDescending { it.finishedAt }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(playBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "رجوع",
                    tint = Color.White
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = "مكتبة التشغيل",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${activeItems.size} نشط • ${records.size} مكتمل",
                    color = playMuted,
                    fontSize = 11.sp
                )
            }

            IconButton(
                onClick = {
                    searchVisible = !searchVisible
                    if (!searchVisible) query = ""
                }
            ) {
                Icon(
                    if (searchVisible) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = "بحث",
                    tint = playAccent
                )
            }
        }

        if (searchVisible) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("ابحث عن ملف أو تنزيل...") },
                shape = RoundedCornerShape(15.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filteredActive.isNotEmpty()) {
                item(key = "active-section") {
                    SectionTitle(
                        title = "التنزيلات النشطة",
                        count = filteredActive.size
                    )
                }

                items(
                    items = filteredActive,
                    key = { "active-${it.id}" }
                ) { item ->
                    ActiveDownloadCard(
                        item = item,
                        onCancel = {
                            runCatching {
                                DownloadService.cancel(context, item.id)
                            }
                        },
                        onRemove = {
                            ActiveDownloads.remove(item.id)
                        }
                    )
                }
            }

            item(key = "history-section") {
                SectionTitle(
                    title = "تم التنزيل",
                    count = filteredRecords.size
                )
            }

            if (filteredRecords.isEmpty()) {
                item(key = "history-empty") {
                    EmptyPlayState(
                        message = if (query.isBlank()) {
                            "لا توجد ملفات مكتملة حتى الآن"
                        } else {
                            "لا توجد نتائج مطابقة"
                        }
                    )
                }
            } else {
                items(
                    items = filteredRecords,
                    key = { "history-${it.id}" }
                ) { record ->
                    CompletedDownloadCard(
                        record = record,
                        onOpen = { openDownload(context, record) },
                        onRemove = {
                            HistoryStore.remove(context, record.id)
                            records = records.filterNot { it.id == record.id }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    count: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            color = playAccent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ActiveDownloadCard(
    item: ActiveDownload,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    val failed = item.state == DlState.FAILED
    val queued = item.state == DlState.QUEUED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(playCard)
            .padding(13.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(43.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF292930)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = playAccent,
                    modifier = Modifier.size(25.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 11.dp)
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        failed -> "فشل التحميل"
                        queued -> "في قائمة الانتظار"
                        else -> "جارٍ التحميل"
                    },
                    color = when {
                        failed -> Color(0xFFFF7168)
                        queued -> playMuted
                        else -> playAccent
                    },
                    fontSize = 11.sp
                )
            }

            if (failed) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "إزالة",
                        tint = Color(0xFFFF7168)
                    )
                }
            } else {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "إلغاء التحميل",
                        tint = Color(0xFFFF7168)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (!failed) {
            if (item.progress in 0..100) {
                LinearProgressIndicator(
                    progress = item.progress.coerceIn(0, 100) / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    color = playAccent,
                    trackColor = Color(0xFF33333A)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    color = playAccent,
                    trackColor = Color(0xFF33333A)
                )
            }
        }

        val details = when {
            failed -> item.message.ifBlank { "تعذر إكمال التنزيل" }
            queued -> "سيبدأ تلقائياً عند توفر مكان"
            item.totalBytes > 0L ->
                "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)} • ${formatBytes(item.speedBytesPerSec)}/ث"
            item.message.isNotBlank() -> item.message
            else -> "جارٍ تجهيز الملف..."
        }

        Text(
            text = details,
            color = if (failed) Color(0xFFFF918A) else playMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 7.dp)
        )
    }
}

@Composable
private fun CompletedDownloadCard(
    record: DownloadRecord,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = null,
        record.thumbPath
    ) {
        value = record.thumbPath?.let { path ->
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(playCard)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(91.dp, 65.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0xFF292930)),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = playAccent,
                    modifier = Modifier.size(29.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 11.dp)
        ) {
            Text(
                text = record.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${formatDuration(record.durationMs)} • ${formatBytes(record.sizeBytes)}",
                color = playMuted,
                fontSize = 11.sp
            )
        }

        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "إزالة من القائمة",
                tint = Color(0xFF85858E)
            )
        }
    }
}

@Composable
private fun EmptyPlayState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 38.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = playAccent,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                color = playMuted,
                fontSize = 13.sp
            )
        }
    }
}

private fun openDownload(context: Context, record: DownloadRecord) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(record.uri), record.mime)
        .addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK
        )

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "لا يوجد تطبيق مناسب لفتح هذا الملف",
            Toast.LENGTH_SHORT
        ).show()
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "تعذر فتح الملف",
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"

    var value = bytes.toDouble()
    val units = arrayOf("KB", "MB", "GB", "TB")
    var index = -1

    do {
        value /= 1024.0
        index++
    } while (value >= 1024.0 && index < units.lastIndex)

    return String.format(Locale.US, "%.1f %s", value, units[index])
}
