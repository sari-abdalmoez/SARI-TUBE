package com.sari.tube.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sari.tube.engine.EpisodeInfo
import com.sari.tube.engine.MediaDetails
import com.sari.tube.engine.MediaDetailsLoader
import com.sari.tube.engine.MetaKind
import com.sari.tube.engine.MetaSearchReport
import com.sari.tube.engine.MetaService
import com.sari.tube.engine.MetadataSearch
import com.sari.tube.engine.SeasonInfo
import com.sari.tube.engine.UnifiedMediaItem
import com.sari.tube.engine.VideoItem
import com.sari.tube.engine.WebSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import com.sari.tube.engine.PageScraper
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

/** Search results for films and series. Each item comes from TMDB, AniList or TVMaze. */
@Composable
fun MediaResultsPanel(
    query: String,
    onOpen: (UnifiedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var report by remember(query) { mutableStateOf<MetaSearchReport?>(null) }
    var loading by remember(query) { mutableStateOf(false) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    var attempt by remember(query) { mutableIntStateOf(0) }

    LaunchedEffect(query, attempt) {
        val q = query.trim()
        report = null
        error = null
        if (q.isEmpty()) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            delay(400) // wait for the user to stop typing
            report = MetadataSearch.search(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message?.takeIf { it.isNotBlank() } ?: "تعذّر البحث، حاول مرة أخرى."
        } finally {
            loading = false
        }
    }

    val current = report
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        when {
            query.isBlank() -> CenterText("اكتب اسم فيلم أو مسلسل للبحث عنه")
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null -> Column(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { attempt++ }) { Text("إعادة المحاولة") }
            }
            current != null -> {
                if (current.failures.isNotEmpty()) {
                    Column(Modifier.padding(top = 8.dp)) {
                        current.failures.forEach { (service, reason) ->
                            Text(
                                "${serviceName(service)}: $reason",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                if (current.items.isEmpty()) {
                    CenterText("لا توجد نتائج لهذا البحث")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(current.items, key = { "${it.service}_${it.serviceId}" }) { item ->
                            MediaCard(item = item, onClick = { onOpen(item) })
                        }
                    }
                }
            }
            else -> Spacer(Modifier.fillMaxSize())
        }
    }
}

/** One card: poster, title, kind, year and rating. Only values the source provided are shown. */
@Composable
fun MediaCard(item: UnifiedMediaItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Poster(url = item.posterUrl, modifier = Modifier.size(84.dp, 126.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindChip(item.kind)
                item.year?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            item.rating?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "★ ${String.format(Locale.US, "%.1f", it)}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
            if (item.overview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.overview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Details page: poster, facts from the source, seasons and episodes, and the watch search. */
@Composable
fun MediaDetailPanel(
    item: UnifiedMediaItem,
    onBack: () -> Unit,
    onWatch: (query: String, title: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var details by remember(item) { mutableStateOf<MediaDetails?>(null) }
    var loadingDetails by remember(item) { mutableStateOf(true) }
    var detailsError by remember(item) { mutableStateOf<String?>(null) }
    var expanded by remember(item) { mutableIntStateOf(-1) }
    var episodes by remember(item) { mutableStateOf<List<EpisodeInfo>?>(null) }
    var episodesError by remember(item) { mutableStateOf<String?>(null) }

    LaunchedEffect(item) {
        loadingDetails = true
        try {
            details = MediaDetailsLoader.details(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            detailsError = e.message?.takeIf { it.isNotBlank() } ?: "تعذّر تحميل التفاصيل."
        } finally {
            loadingDetails = false
        }
    }

    LaunchedEffect(item, expanded) {
        episodes = null
        episodesError = null
        if (expanded <= 0) return@LaunchedEffect
        try {
            episodes = MediaDetailsLoader.episodes(item, expanded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            episodesError = e.message?.takeIf { it.isNotBlank() } ?: "تعذّر تحميل الحلقات."
        }
    }

    val d = details
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("التفاصيل", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        }

        Row(verticalAlignment = Alignment.Top) {
            Poster(url = item.posterUrl, modifier = Modifier.size(120.dp, 180.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                item.altTitles.firstOrNull()?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindChip(item.kind)
                    item.year?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                item.rating?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "★ ${String.format(Locale.US, "%.1f", it)} / 10",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                d?.runtimeMin?.let { FactLine("المدة", "$it دقيقة") }
                d?.seasonCount?.let { FactLine("المواسم", "$it") }
                d?.episodeCount?.let { FactLine("الحلقات", "$it") }
                d?.genres?.takeIf { it.isNotEmpty() }?.let { FactLine("التصنيف", it.joinToString(" • ")) }
                d?.collectionName?.let { FactLine("جزء من", it) }
            }
        }

        if (loadingDetails) {
            Spacer(Modifier.height(10.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        }
        detailsError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        if (item.overview.isNotBlank()) {
            SectionTitle("القصة")
            Text(item.overview, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onWatch(watchQuery(item, null, null), item.title) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("بحث عن مشاهدة")
        }

        val seasons = d?.seasons.orEmpty()
        if (seasons.isNotEmpty()) {
            SectionTitle("المواسم")
            seasons.forEach { season ->
                SeasonRow(
                    season = season,
                    expanded = expanded == season.number,
                    onClick = { expanded = if (expanded == season.number) -1 else season.number }
                )
                if (expanded == season.number) {
                    EpisodesBlock(
                        episodes = episodes,
                        error = episodesError,
                        onWatch = { ep ->
                            onWatch(
                                watchQuery(item, season.number, ep.number),
                                "${item.title} S${season.number}E${ep.number}"
                            )
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Web results for a watch query. DRM services are removed. Each remaining page is checked for a
 * playable video, and pages with video are listed first. Pages that could not be verified come after
 * them, marked, so nothing is hidden without reason.
 */
@Composable
fun WatchResultsPanel(
    query: String,
    onBack: () -> Unit,
    onPlay: (VideoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var verified by remember(query) { mutableStateOf<List<VideoItem>?>(null) }
    var unverified by remember(query) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var filteredHidden by remember(query) { mutableIntStateOf(0) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    var loading by remember(query) { mutableStateOf(true) }
    var attempt by remember(query) { mutableIntStateOf(0) }

    LaunchedEffect(query, attempt) {
        loading = true
        error = null
        verified = null
        try {
            val raw = WebSearch.search(query)
            val usable = raw.filter {
                WebSearch.isRelevantAndSafe(query, it) && !PageScraper.isDrmSite(it.url)
            }
            filteredHidden = raw.size - usable.size
            // Each page is checked in parallel. A page that does not answer in time counts as unverified.
            // Verify a limited first batch. Do not discard other search candidates:
            // yt-dlp and the hidden browser can still resolve them when the user opens one.
            val toVerify = usable.take(6)
            val verifySemaphore = Semaphore(2)
            val checks = coroutineScope {
                toVerify.map { item ->
                    async {
                        verifySemaphore.withPermit {
                            val ok = withTimeoutOrNull(10_000L) {
                                PageScraper.hasPlayableVideo(item.url)
                            } ?: false
                            item to ok
                        }
                    }
                }.awaitAll()
            }
            verified = checks.filter { it.second }.map { it.first }
            unverified = checks.filterNot { it.second }.map { it.first } +
                usable.drop(toVerify.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message?.takeIf { it.isNotBlank() } ?: "تعذّر البحث في الويب."
        } finally {
            loading = false
        }
    }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع", tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Text("نتائج المشاهدة", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(query, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            "تم استبعاد المواقع المحمية (DRM). الصفحات الأولى فيها فيديو تم التحقق منه.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        when {
            loading -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(
                    "جارٍ البحث والتحقق من الصفحات…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            error != null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { attempt++ }) { Text("إعادة المحاولة") }
            }
            else -> {
                val good = verified.orEmpty()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (filteredHidden > 0) {
                        item {
                            Text(
                                "أُخفيت $filteredHidden صفحة غير مرتبطة بالبحث أو غير مناسبة أو محمية بـ DRM",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (good.isEmpty() && unverified.isEmpty()) {
                        item {
                            Text(
                                "لم نجد صفحات تحتوي فيديو قابلاً للتشغيل. جرّب بحثاً آخر.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                            )
                        }
                    }
                    items(good, key = { it.id }) { r -> WatchRow(r, verified = true, onPlay = onPlay) }
                    if (unverified.isNotEmpty()) {
                        item {
                            Text(
                                "صفحات لم نتأكد من وجود فيديو فيها",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(unverified, key = { "u_${it.id}" }) { r -> WatchRow(r, verified = false, onPlay = onPlay) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchRow(item: VideoItem, verified: Boolean, onPlay: (VideoItem) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onPlay(item) }
            .padding(12.dp)
    ) {
        Text(
            item.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (verified) item.channel else "${item.channel} • صفحة مرشحة - التحقق عند التشغيل",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun SeasonRow(season: SeasonInfo, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(season.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            val meta = listOfNotNull(season.episodeCount?.let { "$it حلقة" }, season.year).joinToString(" • ")
            if (meta.isNotBlank()) {
                Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EpisodesBlock(episodes: List<EpisodeInfo>?, error: String?, onWatch: (EpisodeInfo) -> Unit) {
    when {
        error != null -> Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        episodes == null -> CircularProgressIndicator(
            modifier = Modifier.padding(8.dp).size(22.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp
        )
        episodes.isEmpty() -> Text(
            "لا توجد بيانات للحلقات في هذا الموسم",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(8.dp)
        )
        else -> Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp)) {
            episodes.forEach { ep ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onWatch(ep) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${ep.number}. ${ep.name}",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val meta = listOfNotNull(ep.airDate, ep.runtimeMin?.let { "$it دقيقة" }).joinToString(" • ")
                        if (meta.isNotBlank()) {
                            Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                    Icon(Icons.Filled.PlayArrow, contentDescription = "مشاهدة", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun Poster(url: String?, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun KindChip(kind: MetaKind) {
    Text(
        kindLabel(kind),
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun FactLine(label: String, value: String) {
    Text(
        "$label: $value",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp
    )
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun kindLabel(kind: MetaKind): String = when (kind) {
    MetaKind.MOVIE -> "فيلم"
    MetaKind.SERIES -> "مسلسل"
    MetaKind.ANIME -> "أنمي"
}

private fun serviceName(service: MetaService): String = when (service) {
    MetaService.TMDB -> "TMDB"
    MetaService.ANILIST -> "AniList"
    MetaService.TVMAZE -> "TVMaze"
}

/** Query sent to the web search. Episodes use their season and number; films use the year. */
private fun watchQuery(item: UnifiedMediaItem, season: Int?, episode: Int?): String {
    val tail = if (season != null && episode != null) {
        String.format(Locale.US, " S%02dE%02d", season, episode)
    } else {
        item.year?.let { " $it" }.orEmpty()
    }
    return "${item.title}$tail مشاهدة"
}
