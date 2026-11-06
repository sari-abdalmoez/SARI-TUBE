package com.sari.tube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sari.tube.engine.MediaFormats
import com.sari.tube.engine.MediaOption
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.abs

/** Lets the user choose audio or a video quality, with the size each one needs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSheet(url: String, onDismiss: () -> Unit, onConfirm: (MediaOption) -> Unit) {
    var loading by remember(url) { mutableStateOf(true) }
    var errorMsg by remember(url) { mutableStateOf<String?>(null) }
    var options by remember(url) { mutableStateOf<List<MediaOption>>(emptyList()) }
    var selected by remember(url) { mutableStateOf<MediaOption?>(null) }
    var attempt by remember(url) { mutableStateOf(0) }
    var showAllAudio by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, attempt) {
        loading = true
        errorMsg = null
        try {
            val list = MediaFormats.fetch(url)
            options = list
            selected = list.firstOrNull { it.id == "v720" } ?: list.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMsg = e.message ?: "فشل جلب التنسيقات"
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                "تنزيل الفيديو بصيغة",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))

            val err = errorMsg
            when {
                loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                err != null -> Column {
                    Text(err, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    TextButton(onClick = { attempt++ }) {
                        Text("إعادة المحاولة", color = MaterialTheme.colorScheme.primary)
                    }
                }
                else -> {
                    val audio = options.filter { it.isAudio }
                    val video = options.filterNot { it.isAudio }
                    if (audio.isNotEmpty()) {
                        val commonAudio = remember(audio) {
                            val rates = audio.mapNotNull { option ->
                                Regex("""(\\d+)\\s*kbps""", RegexOption.IGNORE_CASE)
                                    .find(option.label)
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.toIntOrNull()
                                    ?.let { it to option }
                            }

                            val chosen = mutableListOf<MediaOption>()
                            listOf(128, 192).forEach { target ->
                                rates.minByOrNull { abs(it.first - target) }
                                    ?.second
                                    ?.let { if (it !in chosen) chosen.add(it) }
                            }

                            (chosen + audio.filterNot { it in chosen }
                                .sortedByDescending { it.sizeBytes ?: 0L }).take(2)
                        }

                        SectionLabel("الصوت")
                        (if (showAllAudio) audio else commonAudio).forEach { option ->
                            OptionRow(option, option == selected) { selected = option }
                        }

                        if (audio.size > 2) {
                            TextButton(onClick = { showAllAudio = !showAllAudio }) {
                                Text(
                                    if (showAllAudio) "إخفاء الجودات الأخرى" else "عرض كل الجودات",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    if (video.isNotEmpty()) {
                        SectionLabel("فيديو")
                        video.forEach { o -> OptionRow(o, o == selected) { selected = o } }
                    }
                    Spacer(Modifier.height(16.dp))
                    GradientAction(
                        text = "تنزيل",
                        enabled = selected != null,
                        onClick = { selected?.let(onConfirm) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun OptionRow(option: MediaOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            option.label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        Text(
            formatSize(option.sizeBytes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun GradientAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFFFFC53D), Color(0xFFFF8A00))))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color(0xFF141414), fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

private fun formatSize(bytes: Long?): String = when {
    bytes == null -> "حجم غير معروف"
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
