package com.mikeos.video.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import com.mikeos.core.agent.MikeAgent
import com.mikeos.core.hive.HiveIdentity
import com.mikeos.video.BuildConfig
import com.mikeos.video.net.VideoCloudClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The deterministic auto-sync engine. It is invoked on EVERY heartbeat (from the app's
 * perception provider) — NOT gated behind the LLM picking a skill — because "keep Mike's
 * memories safe" must happen on a schedule, not when the model feels like it (CLAUDE.md:
 * make proactive features deterministic on the heartbeat).
 *
 * Each tick (throttled to [MIN_INTERVAL_MS]):
 *  1. gate on unmetered Wi-Fi + adequate battery (defaults; overridable by settings),
 *  2. query MediaStore for camera videos,
 *  3. for each not already `synced` in the Room ledger, run [VideoUploader] (a few per tick),
 *  4. write the ledger only after ingest /status confirms complete.
 *
 * Bootstrap-safe: the first ever tick runs immediately (no throttle) so a fresh install starts
 * syncing without waiting 10 minutes.
 */
class SyncManager private constructor(private val appContext: Context) {

    private val ledgerDao = SyncDatabase.get(appContext).dao()
    private val uploader = VideoUploader(appContext)
    private val identity = HiveIdentity("MikeVideo", BuildConfig.DAEMON_BASE_URL)
    private val cloud = VideoCloudClient()

    private val runLock = Mutex()
    private val lastRunAt = AtomicLong(0L)
    @Volatile private var bootstrapped = false

    // Live status for the UI.
    val syncedTotal = AtomicInteger(0)
    val pendingTotal = AtomicInteger(0)
    @Volatile var lastActivity: String = "idle"
        private set
    @Volatile var currentUpload: String? = null
        private set
    @Volatile var currentProgress: Pair<Int, Int>? = null
        private set

    // Settings (in-memory defaults; the UI toggles them).
    @Volatile var autoSyncEnabled = true
    @Volatile var wifiOnly = true

    /** The user-scoped hive agent key used as X-API-KEY, or null before §0 self-registration. */
    private fun apiKey(): String? =
        MikeAgent.get()?.cred?.agentKey ?: identity.load(appContext)?.agentKey

    /**
     * One sync tick. Safe to call every heartbeat — throttled internally. Returns a short
     * human line describing what happened (used as the agent's per-beat perception).
     */
    suspend fun tick(): String {
        if (!autoSyncEnabled) return "auto-sync off"
        val now = System.currentTimeMillis()
        val firstRun = !bootstrapped
        if (!firstRun && now - lastRunAt.get() < MIN_INTERVAL_MS) {
            return "throttled (${syncedTotal.get()} synced, ${pendingTotal.get()} pending)"
        }
        if (!runLock.tryLock()) return "busy"
        try {
            bootstrapped = true
            lastRunAt.set(now)

            val key = apiKey() ?: return "no api key yet (self-registration pending)"

            if (!batteryOk()) { lastActivity = "waiting: low battery"; return lastActivity }
            if (wifiOnly && !unmeteredWifi()) { lastActivity = "waiting: not on wifi"; return lastActivity }

            val videos = CameraVideos.query(appContext)
            syncedTotal.set(runCatching { ledgerDao.syncedCount() }.getOrDefault(0))

            // Which local videos still need syncing (not in the ledger as synced).
            val pending = ArrayList<CameraVideo>()
            for (v in videos) {
                val entry = ledgerDao.byKey(v.ledgerKey)
                if (entry?.synced != true) pending.add(v)
            }
            pendingTotal.set(pending.size)
            if (pending.isEmpty()) { lastActivity = "all ${videos.size} videos synced"; return lastActivity }

            var uploaded = 0
            for (v in pending.take(MAX_PER_TICK)) {
                currentUpload = v.displayName
                currentProgress = null
                lastActivity = "uploading ${v.displayName}"
                val result = uploader.upload(key, v) { done, total ->
                    currentProgress = done to total
                }
                // Persist ledger regardless — synced=true only on Complete (never-trust-200).
                ledgerDao.upsert(
                    SyncEntry(
                        ledgerKey = v.ledgerKey,
                        mediaStoreId = v.mediaStoreId,
                        displayName = v.displayName,
                        size = v.size,
                        dateModified = v.dateModified,
                        videoId = v.resolvedVideoId,
                        uploadId = v.resolvedUploadId,
                        fileHash = v.resolvedFileHash,
                        synced = result is VideoUploader.Result.Complete,
                    )
                )
                when (result) {
                    is VideoUploader.Result.Complete -> {
                        uploaded++
                        Log.i(TAG, "synced ${v.displayName}")
                    }
                    is VideoUploader.Result.Failed ->
                        Log.w(TAG, "upload ${v.displayName} not complete: ${result.reason} (will resume)")
                }
            }
            currentUpload = null
            currentProgress = null
            syncedTotal.set(runCatching { ledgerDao.syncedCount() }.getOrDefault(syncedTotal.get()))
            pendingTotal.set((pending.size - uploaded).coerceAtLeast(0))
            lastActivity = "synced $uploaded this tick (${pendingTotal.get()} pending)"
            return lastActivity
        } finally {
            runLock.unlock()
        }
    }

    /** Force a sync now (the `upload_now` skill / manual button) — bypasses the throttle. */
    suspend fun syncNow(): String {
        lastRunAt.set(0)
        bootstrapped = false
        return tick()
    }

    /** For the `sync_status` skill / UI header. */
    suspend fun statusLine(): String {
        val synced = runCatching { ledgerDao.syncedCount() }.getOrDefault(syncedTotal.get())
        val local = runCatching { CameraVideos.query(appContext).size }.getOrDefault(-1)
        return if (local < 0) "Sync: $synced uploaded. $lastActivity."
        else "Sync: $synced/$local camera videos safe on the cloud. $lastActivity."
    }

    /** Library from the cloud for the UI grid. */
    suspend fun library(): List<VideoCloudClient.Video> {
        val key = apiKey() ?: return emptyList()
        val vids = cloud.listVideos(key)
        if (vids.isNotEmpty()) _library.value = vids
        return vids
    }

    /**
     * Resident, cross-device library list (metadata only: id/title/status/HLS urls —
     * never the media bytes). Kept warm by [refreshLibrary] on every heartbeat so clips
     * Mike shot on his OTHER device are already present when he opens the app.
     */
    private val _library = MutableStateFlow<List<VideoCloudClient.Video>>(emptyList())
    val libraryState: StateFlow<List<VideoCloudClient.Video>> = _library.asStateFlow()

    /**
     * CROSS-DEVICE SYNC (Phase 1): pull the user-scoped video library LIST from the cloud
     * into the resident [libraryState] every beat. This fetches metadata only (the same
     * `/api/videos` the UI grid uses) — it does NOT download any video bytes. Best-effort.
     */
    suspend fun refreshLibrary() {
        val key = apiKey() ?: return
        val vids = runCatching { cloud.listVideos(key) }.getOrNull() ?: return
        // Never trust an empty result as a real refresh — only replace when we got rows.
        if (vids.isNotEmpty()) _library.value = vids
    }

    suspend fun videoDetail(id: String): VideoCloudClient.Video? {
        val key = apiKey() ?: return null
        return cloud.getVideo(key, id)
    }

    suspend fun search(query: String): List<VideoCloudClient.SearchResult> {
        val key = apiKey() ?: return emptyList()
        return cloud.search(key, query)
    }

    /** A creator's public channel (P3). Public endpoint; key sent when present. */
    suspend fun channel(handle: String): VideoCloudClient.ChannelPage? =
        cloud.getChannel(apiKey(), handle)

    /** Subscribe/unsubscribe (P4) -> (subscribed, subscriberCount). */
    suspend fun setSubscribe(handle: String, on: Boolean): Pair<Boolean, Int>? =
        apiKey()?.let { cloud.subscribe(it, handle, on) }

    /** Recent public videos from channels the user follows (P4). */
    suspend fun subsFeed(): List<VideoCloudClient.Video> =
        apiKey()?.let { cloud.subsFeed(it) } ?: emptyList()

    /** The personalized recommendation feed (P6). */
    suspend fun homeFeed(): List<VideoCloudClient.Video> =
        apiKey()?.let { cloud.homeFeed(it) } ?: emptyList()

    /** Related / up-next videos for a given video (P7). */
    suspend fun related(videoId: String): List<VideoCloudClient.Video> =
        cloud.related(apiKey(), videoId)

    /** Set a custom thumbnail from a frame (P13). */
    suspend fun setThumbnail(videoId: String, atSec: Double): Boolean =
        apiKey()?.let { cloud.setThumbnail(it, videoId, atSec) } ?: false

    /** The vertical Shorts feed (P9). */
    suspend fun shorts(): List<VideoCloudClient.Short> = cloud.shorts(apiKey())

    /** Trending / Explore (P11). */
    suspend fun explore(tag: String?): VideoCloudClient.ExplorePage? = cloud.explore(apiKey(), tag)

    /** Creator Studio analytics (P12). */
    suspend fun studioOverview(): VideoCloudClient.StudioOverview? = cloud.studioOverview(apiKey())

    // Notifications (P8)
    suspend fun notifications(): VideoCloudClient.NotificationFeed? = cloud.notifications(apiKey())
    suspend fun markNotificationsRead(id: String?): Boolean =
        apiKey()?.let { cloud.markNotificationsRead(it, id) } ?: false

    // Comments (P5)
    suspend fun comments(videoId: String): VideoCloudClient.CommentThread? =
        cloud.comments(apiKey(), videoId)
    suspend fun postComment(videoId: String, body: String, parentId: String?): Boolean =
        apiKey()?.let { cloud.postComment(it, videoId, body, parentId) } ?: false
    suspend fun deleteComment(commentId: String): Boolean =
        apiKey()?.let { cloud.deleteComment(it, commentId) } ?: false
    suspend fun likeComment(commentId: String, on: Boolean): Pair<Boolean, Int>? =
        apiKey()?.let { cloud.likeComment(it, commentId, on) }
    suspend fun heartComment(commentId: String, on: Boolean): Boolean =
        apiKey()?.let { cloud.heartComment(it, commentId, on) } ?: false

    suspend fun reportView(id: String) = cloud.reportView(id)
    suspend fun setLike(id: String, like: Boolean): Pair<Boolean, Int>? =
        apiKey()?.let { cloud.setLike(it, id, like) }
    suspend fun saveProgress(id: String, posSec: Double, durSec: Double?) {
        apiKey()?.let { cloud.putProgress(it, id, posSec, durSec) }
    }
    suspend fun saveToWatchLater(id: String): Boolean =
        apiKey()?.let { cloud.addToWatchLater(it, id) } ?: false

    // ---- gating -----------------------------------------------------------------------------

    private fun unmeteredWifi(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun batteryOk(): Boolean {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true
        val level = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
            .getOrDefault(100)
        val charging = runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
        }.getOrDefault(false)
        return charging || level < 0 || level >= MIN_BATTERY_PCT
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val MIN_INTERVAL_MS = 5 * 60 * 1000L   // sync at most every ~5 min per beat
        private const val MAX_PER_TICK = 3                    // a few videos per tick (throttle)
        private const val MIN_BATTERY_PCT = 25

        @Volatile private var instance: SyncManager? = null
        fun get(context: Context): SyncManager =
            instance ?: synchronized(this) {
                instance ?: SyncManager(context.applicationContext).also { instance = it }
            }
    }
}
