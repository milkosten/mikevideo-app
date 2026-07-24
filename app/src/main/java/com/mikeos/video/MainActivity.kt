package com.mikeos.video

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
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

                if (player.video == null) {
                    LibraryScreen(
                        state = library,
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

/* ------------------------------- Library grid ------------------------------- */

@Composable
private fun LibraryScreen(
    state: LibraryState,
    onRefresh: () -> Unit,
    onForceSync: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onOpen: (VideoCloudClient.Video) -> Unit,
) {
    Scaffold(containerColor = MikeBg) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            val context = LocalContext.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "MIKEVIDEO",
                        color = MikeAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    Text("your memories, safe & browsable", color = MikeMuted, fontSize = 12.sp)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MikeMuted)
                }
                // MANDATORY: one-tap window into this app's living agent.
                com.mikeos.core.ui.AgentIconButton(
                    onClick = { com.mikeos.core.ui.AgentInspectorActivity.start(context) }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Sync status + settings card.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MikeSurfaceVariant)
                    .padding(14.dp),
            ) {
                Text(
                    state.syncLine.ifBlank { "Sync: starting…" },
                    color = MikeOnSurface,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                SettingRow("Auto-sync new videos", state.autoSync, onAutoSync)
                SettingRow("Wi-Fi only", state.wifiOnly, onWifiOnly)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onForceSync() }
                        .background(MikeAccent.copy(alpha = 0.15f))
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = MikeAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Back up now", color = MikeAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(14.dp))

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
                        Modifier.padding(start = 10.dp).size(14.dp),
                        color = MikeAccent,
                        strokeWidth = 2.dp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (state.videos.isEmpty()) {
                Text(
                    state.notice ?: "No videos.",
                    color = MikeMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(2)) {
                    items(state.videos, key = { it.id }) { v -> VideoCard(v, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MikeOnSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
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

@Composable
private fun VideoCard(v: VideoCloudClient.Video, onOpen: (VideoCloudClient.Video) -> Unit) {
    val ready = v.status == "ready"
    Column(
        Modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = ready) { onOpen(v) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MikeSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val context = LocalContext.current
            if (v.thumbUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(v.thumbUrl).crossfade(true).build(),
                    imageLoader = VideoImages.loader(context),
                    contentDescription = v.filename,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            StatusChip(v.status, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (ready) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            v.filename,
            color = MikeOnSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
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
    Scaffold(containerColor = Color.Black) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MikeAccent)
                }
                Text(v.filename, color = MikeOnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }

            val hls = v.hlsUrl
            if (hls == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.loading) "Loading…" else "This video isn't ready to play yet (${v.status}).",
                        color = MikeMuted,
                        fontSize = 14.sp,
                    )
                }
                return@Column
            }

            val context = LocalContext.current
            // Media3/ExoPlayer over the HLS master. /media is gated on the video_id (not the
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
            DisposableEffect(exo) {
                onDispose { exo.release() }
            }

            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { player = exo; useController = true } },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
            )

            val meta = buildList {
                v.durationSec?.let { add("${it.toInt()}s") }
                if (v.width != null && v.height != null) add("${v.width}×${v.height}")
                v.bytes?.let { add("${it / (1024 * 1024)} MB") }
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = MikeMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
