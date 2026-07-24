package com.mikeos.video

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikeos.video.net.VideoCloudClient
import com.mikeos.video.sync.SyncManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The library grid + sync-header state. */
data class LibraryState(
    val videos: List<VideoCloudClient.Video> = emptyList(),
    val loading: Boolean = false,
    val syncLine: String = "",
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = true,
    val notice: String? = null,
)

/** The player screen state (a `ready` video's HLS master + metadata). */
data class PlayerState(
    val video: VideoCloudClient.Video? = null,
    val loading: Boolean = false,
)

class VideoViewModel(app: Application) : AndroidViewModel(app) {

    private val sync = SyncManager.get(app)
    private val prefs = app.getSharedPreferences("mikevideo_ui", Context.MODE_PRIVATE)

    private val _library = MutableStateFlow(LibraryState(loading = true))
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _player = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = _player.asStateFlow()

    // Library zoom level: how many columns the grid shows. Pinch to change it;
    // persisted so it sticks across launches. 2 = large, 4 = dense.
    private val _gridColumns = MutableStateFlow(prefs.getInt(KEY_COLUMNS, 2).coerceIn(MIN_COLUMNS, MAX_COLUMNS))
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    private var pollJob: Job? = null

    init {
        _library.value = _library.value.copy(autoSync = sync.autoSyncEnabled, wifiOnly = sync.wifiOnly)
        refresh()
        startPolling()
    }

    /** Reload the cloud library + refresh the sync header line. */
    fun refresh() {
        viewModelScope.launch {
            _library.value = _library.value.copy(loading = true, notice = null)
            val vids = sync.library()
            val line = sync.statusLine()
            _library.value = _library.value.copy(
                videos = vids,
                loading = false,
                syncLine = line,
                notice = if (vids.isEmpty()) "No videos yet. New camera clips auto-sync over Wi-Fi." else null,
            )
        }
    }

    /** Light poll so encoding->ready flips and new syncs surface without a manual refresh. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                val vids = sync.library()
                _library.value = _library.value.copy(videos = vids, syncLine = sync.statusLine())
            }
        }
    }

    fun forceSync() {
        viewModelScope.launch {
            _library.value = _library.value.copy(syncLine = "Syncing…")
            sync.syncNow()
            refresh()
        }
    }

    fun setAutoSync(on: Boolean) {
        sync.autoSyncEnabled = on
        _library.value = _library.value.copy(autoSync = on)
    }

    fun setWifiOnly(on: Boolean) {
        sync.wifiOnly = on
        _library.value = _library.value.copy(wifiOnly = on)
    }

    /** Open a video: fetch its detail (HLS master url) before showing the player. */
    fun openVideo(v: VideoCloudClient.Video) {
        _player.value = PlayerState(video = v, loading = true)
        viewModelScope.launch {
            val detail = sync.videoDetail(v.id) ?: v
            _player.value = PlayerState(video = detail, loading = false)
        }
    }

    fun closePlayer() {
        _player.value = PlayerState()
    }

    /** Set the library zoom level (column count), clamped and persisted. */
    fun setGridColumns(n: Int) {
        val c = n.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        if (c != _gridColumns.value) {
            _gridColumns.value = c
            prefs.edit().putInt(KEY_COLUMNS, c).apply()
        }
    }

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 4
        private const val KEY_COLUMNS = "grid_columns"
    }
}
