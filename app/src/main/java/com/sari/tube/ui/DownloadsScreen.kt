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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
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
import com.sari.tube.storage.DownloadRecord
import com.sari.tube.storage.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class DownloadSort { NEWEST, OLDEST, LARGEST }

private val pageBlack = Color(0xFF09090B)
private val cardBlack = Color(0xFF18181D)
private val accentYellow = Color(0xFFFFC53D)
private val mutedText = Color(0xFF9999A3)

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var records by remember { mutableStateOf(HistoryStore.all(ctx)) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleteMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var sort by rememberSaveable { mutableStateOf(DownloadSort.NEWEST) }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val visibleRecords = remember(records, query, sort) {
        val filtered = records.filter {
            it.name.contains(query.trim(), ignoreCase = true)
        }
        when (sort) {
            DownloadSort.NEWEST -> filtered.sortedByDescending { it.finishedAt }
            DownloadSort.OLDEST -> filtered.sortedBy { it.finishedAt }
            DownloadSort.LARGEST -> filtered.sortedByDescending { it.sizeBytes }
        }
    }

    Column(
        Modifier.fillMaxSize().background(pageBlack)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "رجوع", tint = Color.White)
            }

            Column(Modifier.weight(1f)) {
                Text(
                    "تم التنزيل",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${records.size} ملف",
                    color = mutedText,
                    fontSize = 11.sp
                )
            }

            if (deleteMode) {
                TextButton(
                    onClick = {
                        selectedIds = if (selectedIds.size == records.size) {
                            emptySet()
                        } else {
                            records.map { it.id }.toSet()
                        }
                    }
                ) {
                    Text(
                        if (selectedIds.size == records.size) "إلغاء الكل"
                        else "تحديد الكل",
                        color = accentYellow
                    )
                }

                IconButton(
                    onClick = { confirmDelete = true },
                    enabled = selectedIds.isNotEmpty()
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        "حذف المحدد",
                        tint = if (selectedIds.isNotEmpty())
                            Color(0xFFFF6B5F) else Color.Gray
                    )
                }

                IconButton(onClick = {
                    deleteMode = false
                    selectedIds = emptySet()
                }) {
                    Icon(Icons.Filled.Close, "إنهاء التحديد", tint = Color.White)
                }
            } else {
                IconButton(onClick = {
                    searchVisible = !searchVisible
                    if (!searchVisible) query = ""
                }) {
                    Icon(
                        if (searchVisible) Icons.Filled.Close else Icons.Filled.Search,
                        "بحث",
                        tint = Color.White
                    )
                }

                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Filled.MoreVert, "ترتيب", tint = accentYellow)
                    }

                    DropdownMenu(
                        expanded = sortMenu,
                        onDismissRequest = { sortMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("الأحدث أولاً") },
                            onClick = {
                                sort = DownloadSort.NEWEST
                                sortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("الأقدم أولاً") },
                            onClick = {
                                sort = DownloadSort.OLDEST
                                sortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("الأكبر حجماً") },
                            onClick = {
                                sort = DownloadSort.LARGEST
                                sortMenu = false
                            }
                        )
                    }
                }

                IconButton(
                    onClick = {
                        deleteMode = true
                        selectedIds = emptySet()
                    },
                    enabled = records.isNotEmpty()
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        "وضع الحذف",
                        tint = Color(0xFFFF6B5F)
                    )
                }
            }
        }

        if (searchVisible && !deleteMode) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                singleLine = true,
                placeholder = {
                    Text("ابحث في التنزيلات...")
                },
                shape = RoundedCornerShape(16.dp)
            )
        }

        when {
            records.isEmpty() -> {
                EmptyDownloads("لا توجد ملفات منزّلة بعد")
            }

            visibleRecords.isEmpty() -> {
                EmptyDownloads("لا توجد نتائج مطابقة")
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        top = 10.dp,
                        bottom = 20.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleRecords, key = { it.id }) { rec ->
                        DownloadRow(
                            rec = rec,
                            selected = rec.id in selectedIds,
                            selectionMode = deleteMode,
                            onOpen = { openRecord(ctx, rec) },
                            onToggle = {
                                selectedIds = if (rec.id in selectedIds) {
                                    selectedIds - rec.id
                                } else {
                                    selectedIds + rec.id
                                }
                            },
                            onRemove = {
                                HistoryStore.remove(ctx, rec.id)
                                records = HistoryStore.all(ctx)
                            }
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("إزالة التنزيلات المحددة؟") },
            text = {
                Text(
                    "ستُزال العناصر من قائمة التطبيق فقط، ولن تُحذف الملفات الأصلية من الهاتف."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedIds.forEach { id ->
                        HistoryStore.remove(ctx, id)
                    }
                    records = HistoryStore.all(ctx)
                    selectedIds = emptySet()
                    deleteMode = false
                    confirmDelete = false
                }) {
                    Text("إزالة", color = Color(0xFFFF6B5F))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun EmptyDownloads(message: String) {
    Box(
        Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = accentYellow,
                modifier = Modifier.size(42.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(message, color = Color(0xFFAAAAAA), fontSize = 14.sp)
        }
    }
}

@Composable
private fun DownloadRow(
    rec: DownloadRecord,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit
) {
    val thumb by produceState<ImageBitmap?>(
        initialValue = null,
        rec.thumbPath
    ) {
        value = rec.thumbPath?.let { path ->
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
        }
    }

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) Color(0xFF30291B) else cardBlack)
            .clickable {
                if (selectionMode) onToggle() else onOpen()
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(104.dp, 66.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0xFF292930)),
            contentAlignment = Alignment.Center
        ) {
            val image = thumb
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = accentYellow,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        Column(
            Modifier.weight(1f).padding(horizontal = 11.dp)
        ) {
            Text(
                rec.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatDuration(rec.durationMs)}  •  ${formatSize(rec.sizeBytes)}",
                color = mutedText,
                fontSize = 11.sp
            )
        }

        if (selectionMode) {
            Icon(
                Icons.Filled.Check,
                contentDescription = if (selected) "محدد" else "تحديد",
                tint = if (selected) accentYellow else Color(0xFF66666F),
                modifier = Modifier.size(24.dp)
            )
        } else {
            IconButton(onClick = onOpen) {
                Icon(
                    Icons.Filled.PlayArrow,
                    "تشغيل",
                    tint = accentYellow
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    "إزالة من القائمة",
                    tint = Color(0xFF85858E)
                )
            }
        }
    }
}

private fun openRecord(ctx: Context, rec: DownloadRecord) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(rec.uri), rec.mime)
        .addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK
        )

    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            ctx,
            "لا يوجد تطبيق لفتح هذا الملف",
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60

    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L ->
        String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L ->
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
