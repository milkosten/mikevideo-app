package com.mikeos.video

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
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

                if (player.video == null) {
                    LibraryScreen(
                        state = library,
                        columns = columns,
                        onColumns = { vm.setGridColumns(it) },
                        onRefresh = { vm.refresh() },
                        onForceSync = { vm.forceSync() },
                        onAutoSync = { vm.setAutoSync(it) },
                        onWifiOnly = { vm.setWifiOnly(it) },
                        onOpen = { vm.openVideo(it) },
                    )
                } else {
                    PlayerScreen(state = player, onBack = { vm.closePlayer() })
                    BackHandler { vm.closePlayer() }
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
    onRefresh: () -> Unit,
    onForceSync: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onOpen: (VideoCloudClient.Video) -> Unit,
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
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Settings", tint = MikeMuted)
                }
                // MANDATORY: one-tap window into this app's living agent.
                com.mikeos.core.ui.AgentIconButton(
                    onClick = { com.mikeos.core.ui.AgentInspectorActivity.start(context) }
                )
            }

            Spacer(Modifier.height(10.dp))

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
private fun PlayerScreen(state: PlayerState, onBack: () -> Unit) {
    val v = state.video ?: return
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
                ExoPlayer.Builder(context).build().apply {
                    val dsf = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                    val source = HlsMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(hls))
                    setMediaSource(source)
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(exo) { onDispose { exo.release() } }

            var chromeVisible by remember { mutableStateOf(false) }  // start clean; tap to reveal
            var fill by remember { mutableStateOf(false) }          // fit ⇄ zoom(crop-to-fill)
            var landscape by remember { mutableStateOf(false) }
            var infoOpen by remember { mutableStateOf(false) }

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
            if (infoOpen) VideoInfoOverlay(v = v, onClose = { infoOpen = false })
        }
    }
}

@Composable
private fun PlayerTopBar(
    v: VideoCloudClient.Video,
    fill: Boolean,
    landscape: Boolean,
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
        // Just a friendly capture date — no technical noise on the video itself.
        Text(
            friendlyDate(v.takenAt ?: v.createdAt),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
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
private fun VideoInfoOverlay(v: VideoCloudClient.Video, onClose: () -> Unit) {
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
                .padding(28.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MikeSurface)
                .padding(22.dp),
        ) {
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
