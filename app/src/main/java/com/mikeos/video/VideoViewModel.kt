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
    val seekToMs: Long = 0L,          // deep-link target from a search hit
    val related: List<VideoCloudClient.Video> = emptyList(),   // P7 up-next
)

/** Spoken-word search (Phase B). */
data class SearchState(
    val query: String = "",
    val results: List<VideoCloudClient.SearchResult> = emptyList(),
    val loading: Boolean = false,
)

/** A creator's public channel page (P3). Non-null [page] or [loading] => show it. */
data class ChannelState(
    val handle: String = "",
    val loading: Boolean = false,
    val page: VideoCloudClient.ChannelPage? = null,
    val subscribed: Boolean = false,
    val subscriberCount: Int = 0,
)

/** The subscriptions feed (P4). [open] => show the screen. */
data class SubsFeedState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val videos: List<VideoCloudClient.Video> = emptyList(),
)

/** The comments sheet (P5) for the video being watched. [open] => show it. */
data class CommentsState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val videoId: String = "",
    val thread: VideoCloudClient.CommentThread? = null,
)

/** The Shorts feed (P9). [open] => show the swipe pager. */
data class ShortsState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val items: List<VideoCloudClient.Short> = emptyList(),
)

/** The notifications inbox (P8). [open] => show the screen; [unread] drives the bell badge. */
data class NotificationsState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val items: List<VideoCloudClient.Notification> = emptyList(),
    val unread: Int = 0,
)

class VideoViewModel(app: Application) : AndroidViewModel(app) {

    private val sync = SyncManager.get(app)
    private val prefs = app.getSharedPreferences("mikevideo_ui", Context.MODE_PRIVATE)

    private val _library = MutableStateFlow(LibraryState(loading = true))
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _player = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = _player.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    private val _channel = MutableStateFlow(ChannelState())
    val channel: StateFlow<ChannelState> = _channel.asStateFlow()

    private val _subsFeed = MutableStateFlow(SubsFeedState())
    val subsFeed: StateFlow<SubsFeedState> = _subsFeed.asStateFlow()

    // Personalized recommendation feed (P6) — same shape as the subs feed.
    private val _homeFeed = MutableStateFlow(SubsFeedState())
    val homeFeed: StateFlow<SubsFeedState> = _homeFeed.asStateFlow()

    private val _comments = MutableStateFlow(CommentsState())
    val comments: StateFlow<CommentsState> = _comments.asStateFlow()

    private val _notifs = MutableStateFlow(NotificationsState())
    val notifs: StateFlow<NotificationsState> = _notifs.asStateFlow()

    private val _shorts = MutableStateFlow(ShortsState())
    val shorts: StateFlow<ShortsState> = _shorts.asStateFlow()

    // Library zoom level: how many columns the grid shows. Pinch to change it;
    // persisted so it sticks across launches. 2 = large, 4 = dense.
    private val _gridColumns = MutableStateFlow(prefs.getInt(KEY_COLUMNS, 2).coerceIn(MIN_COLUMNS, MAX_COLUMNS))
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    private var pollJob: Job? = null

    init {
        _library.value = _library.value.copy(autoSync = sync.autoSyncEnabled, wifiOnly = sync.wifiOnly)
        refresh()
        startPolling()
        refreshUnread()
    }

    // ---- Notifications (P8) ----------------------------------------------------------
    /** Refresh just the unread count (drives the bell badge). */
    fun refreshUnread() {
        viewModelScope.launch {
            val f = sync.notifications() ?: return@launch
            _notifs.value = _notifs.value.copy(unread = f.unread, items = f.items)
        }
    }

    fun openNotifications() {
        _notifs.value = _notifs.value.copy(open = true, loading = true)
        viewModelScope.launch {
            val f = sync.notifications()
            _notifs.value = NotificationsState(open = true, loading = false,
                items = f?.items ?: emptyList(), unread = f?.unread ?: 0)
        }
    }

    fun closeNotifications() { _notifs.value = _notifs.value.copy(open = false) }

    // ---- Shorts (P9) -----------------------------------------------------------------
    fun openShorts() {
        _shorts.value = ShortsState(open = true, loading = true)
        viewModelScope.launch {
            _shorts.value = ShortsState(open = true, loading = false, items = sync.shorts())
        }
    }

    fun closeShorts() { _shorts.value = ShortsState() }

    fun likeShort(id: String, currentlyLiked: Boolean) {
        viewModelScope.launch {
            val res = sync.setLike(id, !currentlyLiked) ?: return@launch
            _shorts.value = _shorts.value.copy(items = _shorts.value.items.map {
                if (it.id == id) it.copy(liked = res.first, likes = res.second) else it
            })
        }
    }

    fun reportShortView(id: String) { viewModelScope.launch { sync.reportView(id) } }

    fun markAllNotificationsRead() {
        viewModelScope.launch { sync.markNotificationsRead(null); refreshUnread(); openNotificationsReload() }
    }

    private fun openNotificationsReload() {
        viewModelScope.launch {
            val f = sync.notifications()
            _notifs.value = _notifs.value.copy(items = f?.items ?: emptyList(), unread = f?.unread ?: 0)
        }
    }

    /** Tap a notification: mark it read, then navigate to its target. */
    fun onNotificationClick(n: VideoCloudClient.Notification) {
        viewModelScope.launch { sync.markNotificationsRead(n.id); refreshUnread() }
        _notifs.value = _notifs.value.copy(open = false)
        when {
            n.videoId != null -> openVideoById(n.videoId)
            n.channelHandle != null -> openChannel(n.channelHandle)
        }
    }

    /** Open a video by id (fetch detail first) — used from notifications. */
    fun openVideoById(id: String) {
        _player.value = PlayerState(loading = true)
        viewModelScope.launch {
            val detail = sync.videoDetail(id)
            if (detail == null) { _player.value = PlayerState(); return@launch }
            _player.value = PlayerState(video = detail, loading = false)
            launch { sync.reportView(id) }
            launch { val rel = sync.related(id); _player.value = _player.value.copy(related = rel) }
        }
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

    // Autoplay-next preference (P7), persisted; default ON.
    private val _autoplay = MutableStateFlow(prefs.getBoolean(KEY_AUTOPLAY, true))
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()
    fun setAutoplay(on: Boolean) {
        _autoplay.value = on
        prefs.edit().putBoolean(KEY_AUTOPLAY, on).apply()
    }

    /** Open a video: fetch its detail (HLS master url) before showing the player. */
    fun openVideo(v: VideoCloudClient.Video) {
        _player.value = PlayerState(video = v, loading = true)
        viewModelScope.launch {
            val detail = sync.videoDetail(v.id) ?: v
            _player.value = _player.value.copy(video = detail, loading = false)
            launch { sync.reportView(v.id) }        // count a view
            launch { val rel = sync.related(v.id); _player.value = _player.value.copy(related = rel) }
        }
    }

    /** When a video ends: if autoplay is on, open the top related video (P7). */
    fun playNextIfAuto() {
        if (!_autoplay.value) return
        _player.value.related.firstOrNull()?.let { openVideo(it) }
    }

    /** Toggle like; returns (liked, likes) applied to the player state. */
    fun toggleLike(id: String, currentlyLiked: Boolean, onResult: (Boolean, Int) -> Unit) {
        viewModelScope.launch {
            val res = sync.setLike(id, !currentlyLiked) ?: return@launch
            onResult(res.first, res.second)
        }
    }

    fun saveProgress(id: String, posSec: Double, durSec: Double?) {
        viewModelScope.launch { sync.saveProgress(id, posSec, durSec) }
    }

    fun saveToWatchLater(id: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(sync.saveToWatchLater(id)) }
    }

    /** Open from a search hit, deep-linking to the spoken moment. */
    fun openSearchResult(id: String, startSec: Double) {
        _player.value = PlayerState(video = null, loading = true, seekToMs = (startSec * 1000).toLong())
        viewModelScope.launch {
            val detail = sync.videoDetail(id)
            _player.value = PlayerState(video = detail, loading = false, seekToMs = (startSec * 1000).toLong())
            launch { sync.reportView(id) }
            launch { val rel = sync.related(id); _player.value = _player.value.copy(related = rel) }
        }
    }

    /** Debounced spoken-word search. */
    fun setQuery(q: String) {
        _search.value = _search.value.copy(query = q)
        searchJob?.cancel()
        if (q.isBlank()) { _search.value = _search.value.copy(results = emptyList(), loading = false); return }
        searchJob = viewModelScope.launch {
            delay(300)
            _search.value = _search.value.copy(loading = true)
            val res = sync.search(q)
            _search.value = _search.value.copy(results = res, loading = false)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchState()
    }

    fun closePlayer() {
        _player.value = PlayerState()
    }

    /** Open a creator's channel by @handle (from a card/player byline). */
    fun openChannel(handle: String) {
        _channel.value = ChannelState(handle = handle, loading = true)
        viewModelScope.launch {
            val page = sync.channel(handle)
            _channel.value = ChannelState(
                handle = handle, loading = false, page = page,
                subscribed = page?.subscribed ?: false,
                subscriberCount = page?.channel?.subscriberCount ?: 0,
            )
        }
    }

    /** Subscribe/unsubscribe on the open channel; optimistic count update. */
    fun toggleSubscribe(handle: String) {
        val cur = _channel.value
        viewModelScope.launch {
            val res = sync.setSubscribe(handle, !cur.subscribed) ?: return@launch
            _channel.value = _channel.value.copy(subscribed = res.first, subscriberCount = res.second)
        }
    }

    fun closeChannel() {
        _channel.value = ChannelState()
    }

    /** Open / close the subscriptions feed (P4). */
    fun openSubsFeed() {
        _subsFeed.value = SubsFeedState(open = true, loading = true)
        viewModelScope.launch {
            _subsFeed.value = SubsFeedState(open = true, loading = false, videos = sync.subsFeed())
        }
    }

    fun closeSubsFeed() {
        _subsFeed.value = SubsFeedState()
    }

    /** Open / close the recommendation feed (P6). */
    fun openHomeFeed() {
        _homeFeed.value = SubsFeedState(open = true, loading = true)
        viewModelScope.launch {
            _homeFeed.value = SubsFeedState(open = true, loading = false, videos = sync.homeFeed())
        }
    }

    fun closeHomeFeed() {
        _homeFeed.value = SubsFeedState()
    }

    // ---- Comments (P5) ---------------------------------------------------------------
    fun openComments(videoId: String) {
        _comments.value = CommentsState(open = true, loading = true, videoId = videoId)
        reloadComments()
    }

    fun closeComments() { _comments.value = CommentsState() }

    private fun reloadComments() {
        val id = _comments.value.videoId
        if (id.isBlank()) return
        viewModelScope.launch {
            val t = sync.comments(id)
            _comments.value = _comments.value.copy(loading = false, thread = t)
        }
    }

    fun postComment(body: String, parentId: String?) {
        val id = _comments.value.videoId
        if (id.isBlank() || body.isBlank()) return
        viewModelScope.launch { if (sync.postComment(id, body.trim(), parentId)) reloadComments() }
    }

    fun deleteComment(commentId: String) {
        viewModelScope.launch { if (sync.deleteComment(commentId)) reloadComments() }
    }

    fun likeComment(commentId: String, on: Boolean) {
        viewModelScope.launch { sync.likeComment(commentId, on); reloadComments() }
    }

    fun heartComment(commentId: String, on: Boolean) {
        viewModelScope.launch { if (sync.heartComment(commentId, on)) reloadComments() }
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
        private const val KEY_AUTOPLAY = "autoplay_next"
    }
}
