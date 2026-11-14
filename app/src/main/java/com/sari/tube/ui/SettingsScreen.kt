package com.sari.tube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.width
import com.sari.tube.engine.MetadataKeys
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sari.tube.engine.AppPrefs

/** Download settings: how many run at once, and the speed cap. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var parallel by remember { mutableStateOf(AppPrefs.maxParallel(ctx)) }
    var speed by remember { mutableStateOf(AppPrefs.speedKbps(ctx)) }
    var mobileData by remember { mutableStateOf(AppPrefs.allowMobileData(ctx)) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                "إعدادات التنزيل",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SectionTitle("عدد التحميلات المتزامنة")
            listOf(1 to "تحميل واحد", 2 to "تحميلان", 3 to "3 تحميلات").forEach { (n, label) ->
                OptionRow(label, selected = parallel == n) {
                    parallel = n
                    AppPrefs.setMaxParallel(ctx, n)
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("حد سرعة التنزيل")
            listOf(
                0 to "غير محدودة",
                512 to "512 KB/s",
                1024 to "1 MB/s",
                2048 to "2 MB/s",
                4096 to "4 MB/s"
            ).forEach { (kbps, label) ->
                OptionRow(label, selected = speed == kbps) {
                    speed = kbps
                    AppPrefs.setSpeedKbps(ctx, kbps)
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("الشبكة")

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = mobileData,
                    onCheckedChange = {
                        mobileData = it
                        AppPrefs.setAllowMobileData(ctx, it)
                    }
                )

                Spacer(Modifier.weight(1f))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "التنزيل عبر بيانات الهاتف",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (mobileData) {
                            "مسموح عبر بيانات الهاتف وWi-Fi"
                        } else {
                            "Wi-Fi أو Ethernet فقط"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("مفتاح TMDB للأفلام والمسلسلات")
            var tmdbKey by remember { mutableStateOf(MetadataKeys.tmdb().orEmpty()) }
            var tmdbSaved by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = tmdbKey,
                onValueChange = {
                    tmdbKey = it
                    tmdbSaved = false
                },
                singleLine = true,
                label = { Text("مفتاح API") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    MetadataKeys.setTmdb(ctx, tmdbKey)
                    tmdbSaved = true
                }) { Text("حفظ") }
                Spacer(Modifier.width(12.dp))
                Text(
                    if (tmdbSaved) "تم الحفظ" else "",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }
            Text(
                "المفتاح مجاني من themoviedb.org ويُحفظ على هذا الجهاز فقط. بدونه تعمل خدمتا AniList وTVMaze فقط.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "حد السرعة مشترك وتقريبي. وضع Wi-Fi فقط يمنع بدء تنزيل جديد عبر بيانات الهاتف.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}
