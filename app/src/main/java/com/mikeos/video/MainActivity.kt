package com.mikeos.video

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mikeos.core.runtime.HeartbeatService
import com.mikeos.video.agent.VideoMikeAgent
import com.mikeos.video.net.VideoCloudClient
import com.mikeos.video.net.VideoImages
import com.mikeos.video.ui.theme.MikeAccent
import com.mikeos.video.ui.theme.MikeBg
import com.mikeos.video.ui.theme.MikeGreen
import com.mikeos.video.ui.theme.MikeMuted
import com.mikeos.video.ui.theme.MikeOnSurface
import com.mikeos.video.ui.theme.MikeOsTheme
import com.mikeos.video.ui.theme.MikeSurface
import com.mikeos.video.ui.theme.MikeSurfaceVariant

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()
        // Embed the shared MikeAgent runtime (soul + video skills + heartbeat + live hive).
        // The heartbeat's perception provider drives the deterministic auto-sync tick.
        VideoMikeAgent.install(this)

        setContent {
            MikeOsTheme {
                val vm: VideoViewModel = viewModel()
                val library by vm.library.collectAsStateWithLifecycle()
                val player by vm.player.collectAsStateWithLifecycle()
                val columns by vm.gridColumns.collectAsStateWithLifecycle()
                val searchState by vm.search.collectAsStateWithLifecycle()
                val channelState by vm.channel.collectAsStateWithLifecycle()
                val subsFeedState by vm.subsFeed.collectAsStateWithLifecycle()
                val homeFeedState by vm.homeFeed.collectAsStateWithLifecycle()
                val commentsState by vm.comments.collectAsStateWithLifecycle()

                val playerActive = player.video != null || player.loading
                val channelActive = channelState.page != null || channelState.loading

                when {
                    playerActive -> {
                        PlayerScreen(
                            state = player,
                            onBack = { vm.closePlayer() },
                            onLike = { id, liked, cb -> vm.toggleLike(id, liked, cb) },
                            onProgress = { id, pos, dur -> vm.saveProgress(id, pos, dur) },
                            onSave = { id, cb -> vm.saveToWatchLater(id, cb) },
                            onOpenChannel = { h -> vm.closePlayer(); vm.openChannel(h) },
                            onOpenComments = { id -> vm.openComments(id) },
                        )
                        BackHandler { vm.closePlayer() }
                    }
                    channelActive -> {
                        ChannelScreen(
                            state = channelState,
                            onBack = { vm.closeChannel() },
                            onOpen = { vm.openVideo(it) },
                            onSubscribe = { h -> vm.toggleSubscribe(h) },
                        )
                        BackHandler { vm.closeChannel() }
                    }
                    subsFeedState.open -> {
                        FeedScreen(
                            title = "SUBSCRIPTIONS",
                            empty = "No videos yet. Subscribe to a creator to see\ntheir public uploads here.",
                            state = subsFeedState,
                            onBack = { vm.closeSubsFeed() },
                            onOpen = { vm.openVideo(it) },
                        )
                        BackHandler { vm.closeSubsFeed() }
                    }
                    homeFeedState.open -> {
                        FeedScreen(
                            title = "FOR YOU",
                            empty = "No recommendations yet. Subscribe to a creator\nor make a video public.",
                            state = homeFeedState,
                            onBack = { vm.closeHomeFeed() },
                            onOpen = { vm.openVideo(it) },
                        )
                        BackHandler { vm.closeHomeFeed() }
                    }
                    else -> {
                        LibraryScreen(
                            state = library,
                            columns = columns,
                            onColumns = { vm.setGridColumns(it) },
                            search = searchState,
                            onQuery = { vm.setQuery(it) },
                            onOpenResult = { id, start -> vm.openSearchResult(id, start) },
                            onRefresh = { vm.refresh() },
                            onForceSync = { vm.forceSync() },
                            onAutoSync = { vm.setAutoSync(it) },
                            onWifiOnly = { vm.setWifiOnly(it) },
                            onOpen = { vm.openVideo(it) },
                            onOpenSubs = { vm.openSubsFeed() },
                            onOpenForYou = { vm.openHomeFeed() },
                        )
                    }
                }

                if (commentsState.open) {
                    CommentsSheet(
                        state = commentsState,
                        onClose = { vm.closeComments() },
                        onPost = { body, parent -> vm.postComment(body, parent) },
                        onLike = { id, on -> vm.likeComment(id, on) },
                        onHeart = { id, on -> vm.heartComment(id, on) },
                        onDelete = { id -> vm.deleteComment(id) },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        HeartbeatService.start(this)
    }

    override fun onStop() {
        super.onStop()
        HeartbeatService.stop(this)
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

/* ------------------------------- helpers ------------------------------- */

private fun fmtDuration(sec: Double?): String {
    if (sec == null || sec <= 0) return ""
    val s = sec.toInt()
    val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

private fun fmtBytes(b: Long?): String {
    if (b == null || b <= 0) return ""
    val u = listOf("B", "KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (v >= 100 || i == 0) "${v.toInt()} ${u[i]}" else "%.1f %s".format(v, u[i])
}

/** ISO-8601 → a human date+time like "22 Jul 2026 · 09:27", or "" if unparseable. */
private fun friendlyDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        java.time.OffsetDateTime.parse(iso)
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", java.util.Locale.getDefault()))
    }.getOrElse {
        runCatching {
            java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", java.util.Locale.getDefault()))
        }.getOrDefault("")
    }
}

/* ------------------------------- Library ------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    state: LibraryState,
    columns: Int,
    onColumns: (Int) -> Unit,
    search: SearchState,
    onQuery: (String) -> Unit,
    onOpenResult: (String, Double) -> Unit,
    onRefresh: () -> Unit,
    onForceSync: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onOpen: (VideoCloudClient.Video) -> Unit,
    onOpenSubs: () -> Unit = {},
    onOpenForYou: () -> Unit = {},
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    val syncing = state.syncLine.contains("Syncing", ignoreCase = true) ||
        state.syncLine.contains("Backing up", ignoreCase = true)

    Scaffold(containerColor = MikeBg) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 14.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            // Slim top bar — the library is the hero; sync lives behind the ⚙︎ sheet.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "MIKEVIDEO",
                        color = MikeAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                }
                SyncPill(syncing = syncing, onClick = { showSettings = true })
                Spacer(Modifier.size(4.dp))
                IconButton(onClick = onOpenForYou) {
                    Icon(Icons.Filled.Recommend, contentDescription = "For You", tint = MikeMuted)
                }
                IconButton(onClick = onOpenSubs) {
                    Icon(Icons.Filled.Subscriptions, contentDescription = "Subscriptions", tint = MikeMuted)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Settings", tint = MikeMuted)
                }
                // MANDATORY: one-tap window into this app's living agent.
                com.mikeos.core.ui.AgentIconButton(
                    onClick = { com.mikeos.core.ui.AgentInspectorActivity.start(context) }
                )
            }

            Spacer(Modifier.height(10.dp))

            SearchField(search.query, onQuery)
            Spacer(Modifier.height(12.dp))

            if (search.query.isNotBlank()) {
                // Spoken-word search results (Phase B).
                SearchResults(search, onOpenResult)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "LIBRARY · ${state.videos.size}",
                        color = MikeMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    if (state.loading) {
                        CircularProgressIndicator(
                            Modifier.padding(start = 10.dp).size(13.dp),
                            color = MikeAccent,
                            strokeWidth = 2.dp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (state.videos.isEmpty()) {
                    Text(
                        state.notice ?: "No videos.",
                        color = MikeMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                } else {
                    // Two-finger pinch changes the zoom level (column count); single-finger
                    // drag still scrolls because we only consume multi-touch events.
                    val gap = if (columns >= 4) 4.dp else if (columns == 3) 6.dp else 10.dp
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(columns),
                        verticalItemSpacing = gap,
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier
                            .fillMaxSize()
                            .pinchToZoomColumns(columns, onColumns),
                    ) {
                        items(state.videos, key = { it.id }) { v -> VideoCard(v, columns, onOpen) }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MikeSurface,
        ) {
            SettingsSheet(
                state = state,
                onAutoSync = onAutoSync,
                onWifiOnly = onWifiOnly,
                onForceSync = { onForceSync() },
                onRefresh = onRefresh,
            )
        }
    }
}

@Composable
private fun SyncPill(syncing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MikeSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (syncing) {
            CircularProgressIndicator(Modifier.size(13.dp), color = MikeAccent, strokeWidth = 2.dp)
            Spacer(Modifier.size(6.dp))
            Text("Backing up", color = MikeOnSurface, fontSize = 12.sp)
        } else {
            Icon(Icons.Filled.CloudDone, contentDescription = null, tint = MikeGreen, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(6.dp))
            Text("Backed up", color = MikeMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsSheet(
    state: LibraryState,
    onAutoSync: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onForceSync: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
    ) {
        Text("Sync & backup", color = MikeOnSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            state.syncLine.ifBlank { "Sync: starting…" },
            color = MikeMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        SettingRow("Auto-sync new videos", state.autoSync, onAutoSync)
        SettingRow("Wi-Fi only", state.wifiOnly, onWifiOnly)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onForceSync() }
                .background(MikeAccent)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CloudSync, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Back up now", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onRefresh() }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = MikeMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("Refresh library", color = MikeMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MikeOnSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MikeAccent,
            ),
        )
    }
}

/** Two-finger pinch to change the grid zoom level. Only consumes multi-touch, so
 *  single-finger vertical scrolling of the grid keeps working. Pinch OUT → fewer
 *  columns (bigger tiles); pinch IN → more columns (denser). */
@Composable
private fun Modifier.pinchToZoomColumns(columns: Int, onColumns: (Int) -> Unit): Modifier {
    val cols by rememberUpdatedState(columns)
    val set by rememberUpdatedState(onColumns)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            var accum = 1f
            // Watch in the Initial pass so a pinch is caught before the grid's own
            // scroll gesture — but we only ever consume when 2+ fingers are down, so
            // one-finger scrolling is left completely alone.
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        accum *= zoom
                        if (accum > 1.22f) { set(cols - 1); accum = 1f }
                        else if (accum < 0.82f) { set(cols + 1); accum = 1f }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    androidx.compose.material3.TextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text("Search what was said…", color = MikeMuted, fontSize = 14.sp) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MikeMuted) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear", tint = MikeMuted)
                }
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = MikeSurfaceVariant,
            unfocusedContainerColor = MikeSurfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = MikeOnSurface,
            unfocusedTextColor = MikeOnSurface,
            cursorColor = MikeAccent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SearchResults(search: SearchState, onOpenResult: (String, Double) -> Unit) {
    if (search.loading && search.results.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            CircularProgressIndicator(Modifier.size(15.dp), color = MikeAccent, strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text("Searching…", color = MikeMuted, fontSize = 13.sp)
        }
        return
    }
    if (search.results.isEmpty()) {
        Text("No spoken matches for “${search.query}”.", color = MikeMuted, fontSize = 13.sp,
            modifier = Modifier.padding(top = 16.dp))
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        search.results.forEach { r -> SearchResultRow(r, onOpenResult) }
    }
}

@Composable
private fun SearchResultRow(r: VideoCloudClient.SearchResult, onOpenResult: (String, Double) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MikeSurfaceVariant)
            .clickable { onOpenResult(r.id, r.matchStart) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 108.dp, height = 62.dp).clip(RoundedCornerShape(9.dp)).background(MikeBg),
            contentAlignment = Alignment.Center,
        ) {
            val context = LocalContext.current
            if (r.thumbUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(r.thumbUrl).crossfade(true).build(),
                    imageLoader = VideoImages.loader(context),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, color = MikeOnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val snippet = buildString {
                append(fmtDuration(r.matchStart))
                r.matchText?.let { append("  ·  "); append(it) }
            }
            Text(snippet, color = MikeMuted, fontSize = 12.5.sp, maxLines = 2,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun VideoCard(v: VideoCloudClient.Video, columns: Int, onOpen: (VideoCloudClient.Video) -> Unit) {
    val ready = v.status == "ready"
    // Card matches the video's DISPLAY aspect (portrait tall, landscape wide) → the
    // staggered grid respects orientation and the thumbnail never gets cropped.
    val aspect = v.aspectRatioF.coerceIn(0.55f, 1.9f)
    // Scale the tile chrome down as tiles get smaller (denser zoom).
    val radius = if (columns >= 4) 9.dp else if (columns == 3) 11.dp else 14.dp
    val playSize = if (columns >= 4) 22.dp else if (columns == 3) 30.dp else 38.dp
    val inset = if (columns >= 4) 5.dp else if (columns == 3) 6.dp else 8.dp
    // A clean, Photos-style tile: just the frame + duration. The filename is a
    // technical detail (e.g. "VID_20260722.mp4") — never surfaced to the viewer.
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(radius))
            .background(MikeSurfaceVariant)
            .clickable(enabled = ready) { onOpen(v) },
        contentAlignment = Alignment.Center,
    ) {
        val context = LocalContext.current
        if (v.thumbUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(v.thumbUrl).crossfade(true).build(),
                imageLoader = VideoImages.loader(context),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!ready) {
            StatusChip(v.status, Modifier.align(Alignment.TopEnd).padding(inset))
        } else {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(playSize),
            )
            val dur = fmtDuration(v.durationSec)
            if (dur.isNotEmpty() && columns < 4) {   // hide the badge when tiles get tiny
                Text(
                    dur,
                    color = Color.White,
                    fontSize = if (columns == 3) 10.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(inset)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: String, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        "ready" -> "READY" to MikeGreen
        "encoding" -> "ENCODING" to MikeAccent
        "uploading" -> "UPLOADING" to MikeMuted
        "failed" -> "FAILED" to Color(0xFFE5484D)
        else -> status.uppercase() to MikeMuted
    }
    Text(
        label,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/* ------------------------------- Player ------------------------------- */

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerScreen(
    state: PlayerState,
    onBack: () -> Unit,
    onLike: (String, Boolean, (Boolean, Int) -> Unit) -> Unit = { _, _, _ -> },
    onProgress: (String, Double, Double?) -> Unit = { _, _, _ -> },
    onSave: (String, (Boolean) -> Unit) -> Unit = { _, _ -> },
    onOpenChannel: (String) -> Unit = {},
    onOpenComments: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Immersive: hide the system bars while watching; restore + unlock orientation on exit.
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val v = state.video
    if (v == null) {
        // Loading a search hit — spinner over black until the detail arrives.
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CircularProgressIndicator(color = MikeAccent, modifier = Modifier.align(Alignment.Center))
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val hls = v.hlsUrl
        if (hls == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.loading) {
                    CircularProgressIndicator(color = MikeAccent)
                } else {
                    Text(
                        "This video isn't ready to play yet (${v.status}).",
                        color = MikeMuted, fontSize = 14.sp,
                    )
                }
            }
        } else {
            // ExoPlayer over the HLS master. /media is gated on the video_id (not the
            // X-API-KEY), so the default HTTP data source needs no auth header.
            val exo = remember(hls) {
                val dsf = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                val itemB = MediaItem.Builder().setUri(hls)
                // Side-load the WebVTT subtitle track (Phase B) — shows a CC toggle + captions.
                v.captionsUrl?.let { cap ->
                    itemB.setSubtitleConfigurations(listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(cap))
                            .setMimeType(MimeTypes.TEXT_VTT)
                            .setLanguage(v.spokenLanguage ?: "und")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()))
                }
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
                    .build().apply {
                        setMediaItem(itemB.build())
                        prepare()
                        // deep-link from search, else resume from saved watch position
                        if (state.seekToMs > 0) seekTo(state.seekToMs)
                        else v.progressSec?.let { if (it > 5) seekTo((it * 1000).toLong()) }
                        playWhenReady = true
                    }
            }
            DisposableEffect(exo) {
                onDispose {
                    val pos = exo.currentPosition / 1000.0
                    if (pos > 1) onProgress(v.id, pos, if (exo.duration > 0) exo.duration / 1000.0 else null)
                    exo.release()
                }
            }

            var chromeVisible by remember { mutableStateOf(false) }  // start clean; tap to reveal
            var fill by remember { mutableStateOf(false) }          // fit ⇄ zoom(crop-to-fill)
            var landscape by remember { mutableStateOf(false) }
            var infoOpen by remember { mutableStateOf(false) }
            var liked by remember(v.id) { mutableStateOf(v.liked) }
            var likes by remember(v.id) { mutableStateOf(v.likes) }

            // Full-bleed video surface — RESIZE_MODE_FIT contains any aspect (portrait fills
            // vertically, landscape fills horizontally); no more tiny 16:9 box + black void.
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exo
                        useController = true
                        controllerAutoShow = false        // don't pop the controls when playback starts
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        keepScreenOn = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                chromeVisible = visibility == View.VISIBLE
                            }
                        )
                        hideController()                  // start with a clean, chrome-free frame
                    }
                },
                update = { pv ->
                    pv.resizeMode =
                        if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Top overlay chrome — fades in/out in lock-step with the player controls.
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                PlayerTopBar(
                    v = v,
                    fill = fill,
                    landscape = landscape,
                    liked = liked,
                    likes = likes,
                    onLike = { onLike(v.id, liked) { nl, nc -> liked = nl; likes = nc } },
                    onSaveClick = { onSave(v.id) { ok ->
                        Toast.makeText(context, if (ok) "Saved to Watch Later" else "Couldn't save", Toast.LENGTH_SHORT).show() } },
                    onComments = { onOpenComments(v.id) },
                    onBack = onBack,
                    onInfo = { infoOpen = true },
                    onToggleFill = { fill = !fill },
                    onToggleRotate = {
                        landscape = !landscape
                        activity?.requestedOrientation =
                            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            else ActivityInfo.SCREEN_ORIENTATION_USER
                    },
                )
            }

            // Details, on demand only — the video stays clean until you ask for them.
            if (infoOpen) VideoInfoOverlay(
                v = v,
                onSeek = { ms -> exo.seekTo(ms); exo.play(); infoOpen = false },
                onClose = { infoOpen = false },
                onOpenChannel = { h -> infoOpen = false; onOpenChannel(h) },
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    v: VideoCloudClient.Video,
    fill: Boolean,
    landscape: Boolean,
    liked: Boolean,
    likes: Int,
    onLike: () -> Unit,
    onSaveClick: () -> Unit,
    onComments: () -> Unit,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onToggleFill: () -> Unit,
    onToggleRotate: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.66f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        // The AI title if we have one (human, not the filename), else the capture date.
        Text(
            v.aiTitle?.takeUnless { it.isBlank() } ?: friendlyDate(v.takenAt ?: v.createdAt),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        // Like (👍) with count.
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { onLike() }
                .padding(horizontal = 8.dp, vertical = 6.dp)) {
            Icon(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = "Like", tint = if (liked) MikeAccent else Color.White,
                modifier = Modifier.size(20.dp))
            if (likes > 0) Text(" $likes", color = Color.White, fontSize = 13.sp)
        }
        IconButton(onClick = onComments) {
            Icon(Icons.AutoMirrored.Filled.Comment, contentDescription = "Comments", tint = Color.White)
        }
        IconButton(onClick = onSaveClick) {
            Icon(Icons.Filled.PlaylistAdd, contentDescription = "Save to Watch Later", tint = Color.White)
        }
        IconButton(onClick = onToggleFill) {
            Icon(
                if (fill) Icons.Outlined.Fullscreen else Icons.Outlined.CropFree,
                contentDescription = "Toggle fill",
                tint = Color.White,
            )
        }
        IconButton(onClick = onToggleRotate) {
            Icon(
                if (landscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = "Rotate",
                tint = Color.White,
            )
        }
        IconButton(onClick = onInfo) {
            Icon(Icons.Outlined.Info, contentDescription = "Information", tint = Color.White)
        }
    }
}

/** On-demand technical details — the stuff a curious user *can* look up, but that never
 *  clutters the video. Tap the ⓘ to open; tap anywhere to dismiss. */
@Composable
private fun VideoInfoOverlay(
    v: VideoCloudClient.Video,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onOpenChannel: (String) -> Unit = {},
) {
    val rows = buildList {
        friendlyDate(v.takenAt ?: v.createdAt).takeIf { it.isNotEmpty() }?.let { add("Recorded" to it) }
        if (v.dispW != null && v.dispH != null) {
            val orient = v.orientation?.replaceFirstChar { it.uppercase() }
            add("Resolution" to ("${v.dispW} × ${v.dispH}" + (orient?.let { " · $it" } ?: "")))
        }
        v.aspectRatio?.let { add("Aspect ratio" to it) }
        fmtDuration(v.durationSec).takeIf { it.isNotEmpty() }?.let { add("Duration" to it) }
        v.fps?.let { add("Frame rate" to "${it.toInt()} fps") }
        v.videoCodec?.let { add("Video" to it.uppercase()) }
        add("Audio" to if (v.hasAudio) (v.audioCodec?.uppercase() ?: "Yes") else "Silent")
        if (v.isHdr) add("HDR" to "Yes")
        fmtBytes(v.bytes).takeIf { it.isNotEmpty() }?.let { add("Size" to it) }
        add("File" to v.filename)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MikeSurface)
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
                // Swallow taps inside the card so they don't dismiss the overlay.
                .clickable(enabled = false) {},
        ) {
            // Channel byline (P3) — the creator, tappable to open their channel.
            v.channelHandle?.let { handle ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenChannel(handle) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelAvatar(v.channelName ?: handle, v.channelAvatarUrl, 40.dp)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.channelName ?: "@$handle", color = MikeOnSurface,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("@$handle", color = MikeMuted, fontSize = 12.5.sp, maxLines = 1)
                    }
                    Text("View channel ›", color = MikeAccent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(16.dp))
            }

            // AI layer (Phase C): title, summary, tags, and clickable chapters.
            v.aiTitle?.takeUnless { it.isBlank() }?.let {
                Text(it, color = MikeOnSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            v.aiSummary?.takeUnless { it.isBlank() }?.let {
                Text(it, color = MikeMuted, fontSize = 13.5.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(10.dp))
            }
            if (v.aiTags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    v.aiTags.forEach { tag ->
                        Text(
                            tag, color = MikeAccent, fontSize = 11.5.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(MikeAccent.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            if (v.aiChapters.isNotEmpty()) {
                Text("CHAPTERS", color = MikeMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                v.aiChapters.forEach { ch ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { onSeek((ch.start * 1000).toLong()) }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(fmtDuration(ch.start), color = MikeAccent, fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                        Text(ch.title, color = MikeOnSurface, fontSize = 13.5.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Text("Information", color = MikeOnSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(label, color = MikeMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        value,
                        color = MikeOnSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }
        }
    }
}

/* ------------------------------- Channel (P3) ------------------------------- */

/** A round creator avatar: the image if set, else the first letter on the accent. */
@Composable
private fun ChannelAvatar(name: String, avatarUrl: String?, size: Dp) {
    val context = LocalContext.current
    Box(
        Modifier.size(size).clip(CircleShape).background(MikeAccent),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(avatarUrl).crossfade(true).build(),
                imageLoader = VideoImages.loader(context),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                name.trim().take(1).uppercase(),
                color = Color.White,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A creator's public channel: profile header + subscribe + their videos (P3/P4). */
@Composable
private fun ChannelScreen(
    state: ChannelState,
    onBack: () -> Unit,
    onOpen: (VideoCloudClient.Video) -> Unit,
    onSubscribe: (String) -> Unit = {},
) {
    Scaffold(containerColor = MikeBg) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp).statusBarsPadding(),
        ) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MikeOnSurface)
                }
                Text("CHANNEL", color = MikeMuted, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            val page = state.page
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MikeAccent)
                }
                page == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Channel not found.", color = MikeMuted, fontSize = 14.sp)
                }
                else -> {
                    val c = page.channel
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelAvatar(c.displayName, c.avatarUrl, 76.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.displayName, color = MikeOnSurface, fontSize = 22.sp,
                                fontWeight = FontWeight.Bold, maxLines = 2)
                            Text("@${c.handle}", color = MikeMuted, fontSize = 13.sp)
                            val subs = "${state.subscriberCount} subscriber${if (state.subscriberCount == 1) "" else "s"}"
                            val vs = "${c.videoCount} video${if (c.videoCount == 1) "" else "s"}"
                            Text("$subs · $vs · ${c.totalViews} view${if (c.totalViews == 1L) "" else "s"}",
                                color = MikeMuted, fontSize = 12.5.sp)
                        }
                    }
                    if (!c.bio.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(c.bio, color = MikeOnSurface, fontSize = 13.5.sp, lineHeight = 19.sp)
                    }
                    if (!page.isMe) {
                        Spacer(Modifier.height(14.dp))
                        SubscribeButton(subscribed = state.subscribed, onClick = { onSubscribe(c.handle) })
                    }
                    Spacer(Modifier.height(18.dp))
                    if (page.videos.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                            Text("No public videos yet.", color = MikeMuted, fontSize = 14.sp)
                        }
                    } else {
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(2),
                            verticalItemSpacing = 10.dp,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(page.videos, key = { it.id }) { v -> VideoCard(v, 2, onOpen) }
                        }
                    }
                }
            }
        }
    }
}

/** YouTube-style Subscribe / Subscribed pill (P4). */
@Composable
private fun SubscribeButton(subscribed: Boolean, onClick: () -> Unit) {
    val bg = if (subscribed) MikeSurfaceVariant else MikeOnSurface
    val fg = if (subscribed) MikeMuted else MikeBg
    Text(
        if (subscribed) "Subscribed ✓" else "Subscribe",
        color = fg,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** A generic public-video feed screen — used by both Subscriptions (P4) and For You (P6). */
@Composable
private fun FeedScreen(
    title: String,
    empty: String,
    state: SubsFeedState,
    onBack: () -> Unit,
    onOpen: (VideoCloudClient.Video) -> Unit,
) {
    Scaffold(containerColor = MikeBg) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp).statusBarsPadding(),
        ) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MikeOnSurface)
                }
                Text(title, color = MikeMuted, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            Spacer(Modifier.height(10.dp))
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MikeAccent)
                }
                state.videos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        empty,
                        color = MikeMuted, fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    verticalItemSpacing = 10.dp,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.videos, key = { it.id }) { v -> VideoCard(v, 2, onOpen) }
                }
            }
        }
    }
}

/* ------------------------------- Comments (P5) ------------------------------- */

/** Compact "3h", "2d" relative time for comments. */
private fun agoShort(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val t = runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }
        .getOrElse { runCatching { java.time.Instant.parse(iso) }.getOrNull() } ?: return ""
    val s = (java.time.Instant.now().epochSecond - t.epochSecond).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        s < 2592000 -> "${s / 86400}d ago"
        else -> "${s / 2592000}mo ago"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(
    state: CommentsState,
    onClose: () -> Unit,
    onPost: (String, String?) -> Unit,
    onLike: (String, Boolean) -> Unit,
    onHeart: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val thread = state.thread
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MikeSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).heightIn(min = 240.dp, max = 640.dp)) {
            Text("${thread?.count ?: 0} Comments", color = MikeOnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MikeAccent)
                }
                thread == null -> Text("Couldn't load comments.", color = MikeMuted, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 24.dp))
                thread.comments.isEmpty() -> Text("No comments yet — be the first.", color = MikeMuted,
                    fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                else -> Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    thread.comments.forEach { c ->
                        CommentItem(
                            c = c, isOwner = thread.isOwner, isReply = false,
                            onLike = onLike, onHeart = onHeart, onDelete = onDelete,
                            replying = replyTo == c.id,
                            onReplyToggle = { replyTo = if (replyTo == c.id) null else c.id },
                            onSubmitReply = { txt -> onPost(txt, c.id); replyTo = null },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (thread?.canComment == true) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
                ) {
                    androidx.compose.material3.TextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text("Add a comment…", color = MikeMuted, fontSize = 14.sp) },
                        maxLines = 4, shape = RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = MikeSurfaceVariant,
                            unfocusedContainerColor = MikeSurfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = MikeOnSurface, unfocusedTextColor = MikeOnSurface,
                            cursorColor = MikeAccent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Post", color = if (draft.isBlank()) MikeMuted else MikeAccent,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.clickable(enabled = draft.isNotBlank()) {
                            onPost(draft, null); draft = ""
                        }.padding(14.dp),
                    )
                }
            } else {
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun CommentItem(
    c: VideoCloudClient.Comment,
    isOwner: Boolean,
    isReply: Boolean,
    onLike: (String, Boolean) -> Unit,
    onHeart: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    replying: Boolean,
    onReplyToggle: () -> Unit,
    onSubmitReply: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ChannelAvatar(c.authorName, c.authorAvatar, if (isReply) 28.dp else 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.authorName, color = MikeOnSurface, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(agoShort(c.createdAt), color = MikeMuted, fontSize = 11.sp)
                if (c.hearted) { Spacer(Modifier.width(6.dp)); Text("❤", fontSize = 11.sp) }
            }
            Text(c.body, color = MikeOnSurface, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .clickable { onLike(c.id, !c.liked) }.padding(horizontal = 6.dp, vertical = 5.dp),
                ) {
                    Icon(if (c.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = "Like", tint = if (c.liked) MikeAccent else MikeMuted,
                        modifier = Modifier.size(15.dp))
                    if (c.likeCount > 0) Text(" ${c.likeCount}", color = MikeMuted, fontSize = 12.sp)
                }
                if (!isReply) Text("Reply", color = MikeMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onReplyToggle() }
                        .padding(horizontal = 8.dp, vertical = 5.dp))
                if (isOwner) Text(if (c.hearted) "♥" else "♡", color = if (c.hearted) MikeAccent else MikeMuted,
                    fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .clickable { onHeart(c.id, !c.hearted) }.padding(horizontal = 8.dp, vertical = 3.dp))
                if (c.canDelete) Text("Delete", color = MikeMuted, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onDelete(c.id) }
                        .padding(horizontal = 8.dp, vertical = 5.dp))
            }
            if (replying) {
                var rdraft by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    androidx.compose.material3.TextField(
                        value = rdraft, onValueChange = { rdraft = it },
                        placeholder = { Text("Add a reply…", color = MikeMuted, fontSize = 13.sp) },
                        maxLines = 3, shape = RoundedCornerShape(12.dp),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = MikeSurfaceVariant,
                            unfocusedContainerColor = MikeSurfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = MikeOnSurface, unfocusedTextColor = MikeOnSurface,
                            cursorColor = MikeAccent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text("Send", color = if (rdraft.isBlank()) MikeMuted else MikeAccent,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.clickable(enabled = rdraft.isNotBlank()) { onSubmitReply(rdraft) }.padding(12.dp))
                }
            }
            c.replies.forEach { rc ->
                CommentItem(
                    c = rc, isOwner = isOwner, isReply = true,
                    onLike = onLike, onHeart = onHeart, onDelete = onDelete,
                    replying = false, onReplyToggle = {}, onSubmitReply = {},
                )
            }
        }
    }
}
