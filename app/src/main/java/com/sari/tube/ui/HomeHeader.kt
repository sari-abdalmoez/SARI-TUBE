package com.sari.tube.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BrandYellow = Color(0xFFFFC53D)
private val BrandInk = Color(0xFF141414)
private val HintGray = Color(0xFF8A8A8A)
private val IconGray = Color(0xFF9A9AA5)

@Composable
fun AppTitle(text: String = "SARI LOOP", modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sari-loop-title")
    val animatedColor by transition.animateColor(
        initialValue = Color(0xFFFFD54F),
        targetValue = Color(0xFFFF6D00),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sari-loop-color"
    )

    Text(
        text = text,
        modifier = modifier,
        color = animatedColor,
        fontSize = 48.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp,
        textAlign = TextAlign.Center
    )
}

@Composable
fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDownloadLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier
                .fillMaxWidth()
                .height(68.dp)
                .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(50))
                .padding(start = 8.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(BrandYellow)
                    .clickable(onClick = onSearch),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "بحث",
                    tint = BrandInk,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (value.isEmpty()) {
                    Text(
                        "أغاني سودانية",
                        color = HintGray,
                        fontSize = 18.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 18.sp,
                        textAlign = TextAlign.End
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Icon(
                DownloadArrow,
                contentDescription = "تنزيل من الرابط",
                tint = IconGray,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onDownloadLink)
            )
        }
    }
}

@Composable
fun HomeHeader(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDownloadLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AppTitle()
        Spacer(Modifier.height(48.dp))
        SearchPill(
            value = value,
            onValueChange = onValueChange,
            onSearch = onSearch,
            onDownloadLink = onDownloadLink
        )
    }
}
