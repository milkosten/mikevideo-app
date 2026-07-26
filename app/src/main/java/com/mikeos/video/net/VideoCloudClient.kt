package com.mikeos.video.net

import android.util.Log
import com.mikeos.video.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to **mikevideo-cloud** — the control plane (`video.osmike.com`) that authorizes
 * uploads, mints ingest tickets, owns the Postgres metadata, runs the ffmpeg encode worker,
 * and serves the watch API + HLS. Per-user, scoped by the hive agent key.
 *
 * Auth: every `/api/` call carries `X-API-KEY: <hive agent key>` (from
 * [com.mikeos.core.hive.HiveIdentity] / the installed [com.mikeos.core.agent.MikeAgent]);
 * the cloud resolves it to Mike's `user_id` via mikeoscomputers.
 *
 * TLS: valid public cert behind Caddy, so a STANDARD OkHttpClient (+ DoH for flaky system
 * DNS) is used — never the loopback trust-all client.
 *
 * Media (`/media/{user_id}/{video_id}/…`, i.e. thumb + HLS) is auth-gated on the unguessable
 * video_id (a v4 uuid) — NOT on X-API-KEY (confirmed in the cloud's serve_media). So thumbs
 * and the player fetch those absolute URLs with no key header. We only prefix the relative
 * `thumb_url`/`hls_url` the API returns with [BuildConfig.VIDEO_CLOUD_URL].
 *
 * House rules honoured: never-trust-200 (POST /api/videos verifies a real ticket+upload_id;
 * status is confirmed against the ingest /status), taken_at is ISO-8601, DoH on the client.
 */
class VideoCloudClient(
    val baseUrl: String = BuildConfig.VIDEO_CLOUD_URL.trimEnd('/'),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** A video row from the library (`GET /api/videos`) / detail (`GET /api/videos/{id}`).
     *
     * IMPORTANT: `width`/`height` are the CODED pixel size (as stored). For anything the
     * user SEES — the player box, the thumbnail aspect, the resolution readout — use
     * [dispW]/[dispH]/[aspect], which are the display dims (coded dims with the rotation
     * matrix applied). Phone portrait clips are coded 1920x1080 rot=90 → display 1080x1920. */
    data class Video(
        val id: String,
        val filename: String,
        val status: String,               // uploading | encoding | ready | failed
        val durationSec: Double?,
        val width: Int?,                  // coded width
        val height: Int?,                 // coded height
        val displayWidth: Int?,           // post-rotation width (what to render)
        val displayHeight: Int?,          // post-rotation height (what to render)
        val orientation: String?,         // portrait | landscape | square
        val aspectRatio: String?,         // reduced display AR, e.g. "9:16"
        val rotation: Int?,               // 0 | 90 | 180 | 270
        val fps: Double?,
        val videoCodec: String?,
        val audioCodec: String?,
        val hasAudio: Boolean,
        val isHdr: Boolean,
        // Speech → text (Phase A/B) + AI layer (Phase C).
        val spokenLanguage: String?,      // en/fr/sv (null until transcribed)
        val transcriptStatus: String?,    // pending|running|ready|no_speech|failed
        val captionsUrl: String?,         // absolute WebVTT url (detail only, when ready)
        val aiTitle: String?,
        val aiSummary: String?,
        val aiTags: List<String>,
        val aiChapters: List<Chapter>,
        val enrichStatus: String?,
        val viewCount: Long,
        val likes: Int,
        val liked: Boolean,
        val progressSec: Double?,
        val bytes: Long?,
        val thumbUrl: String?,            // absolute (prefixed) or null
        val hlsUrl: String?,              // absolute (prefixed) or null (detail only, when ready)
        val takenAt: String?,
        val createdAt: String?,
        val error: String?,
    ) {
        /** A human-facing title: the AI title if we have one, else the raw filename. */
        val displayTitle: String get() = aiTitle?.takeUnless { it.isBlank() } ?: filename
        /** Display width, falling back to coded width. */
        val dispW: Int? get() = displayWidth ?: width
        /** Display height, falling back to coded height. */
        val dispH: Int? get() = displayHeight ?: height
        /** Display aspect ratio as a float (w/h). Defaults to 16:9 when unknown. */
        val aspectRatioF: Float
            get() {
                val w = dispW; val h = dispH
                return if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else 16f / 9f
            }
        val isPortrait: Boolean get() = (dispH ?: 0) > (dispW ?: 0)
    }

    /** A chapter marker from the AI layer. */
    data class Chapter(val start: Double, val title: String)

    /** One hit from `GET /api/search` — a video + the best spoken segment + timestamp. */
    data class SearchResult(
        val id: String,
        val title: String,
        val language: String?,
        val thumbUrl: String?,
        val matchStart: Double,        // seconds — deep-link the player here
        val matchText: String?,
    )

    /** The ticket + ingest coordinates minted by `POST /api/videos`. */
    data class Ticket(
        val videoId: String,
        val uploadId: String,
        val ingestUrl: String,
        val ticket: String,
        val chunkSize: Int,
    )

    private fun req(apiKey: String, path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl$path")
            .header("X-API-KEY", apiKey)
            .header("Accept", "application/json")

    private fun abs(url: String?): String? =
        when {
            url.isNullOrBlank() -> null
            url.startsWith("http") -> url
            url.startsWith("/") -> "$baseUrl$url"
            else -> "$baseUrl/$url"
        }

    /**
     * Step 1 of the upload flow: reserve a video row + mint an ingest ticket.
     * `POST /api/videos {filename, content_type, total_size, file_hash:"sha256:…",
     * chunk_size, count, taken_at}` -> `{video_id, upload_id, ingest_url, ticket, chunk_size}`.
     *
     * Never-trust-200: returns null unless the body carries a real upload_id + ticket.
     */
    suspend fun createVideo(
        apiKey: String,
        filename: String,
        contentType: String,
        totalSize: Long,
        fileHashSha256: String,          // "sha256:…"
        chunkSize: Int,
        count: Int,
        takenAtIso: String?,
    ): Ticket? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("filename", filename)
            .put("content_type", contentType)
            .put("total_size", totalSize)          // int, never a stringified ""
            .put("file_hash", fileHashSha256)
            .put("chunk_size", chunkSize)
            .put("count", count)
            .apply { if (!takenAtIso.isNullOrBlank()) put("taken_at", takenAtIso) }
            .toString().toRequestBody(jsonMedia)
        try {
            client.newCall(req(apiKey, "/api/videos").post(payload).build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "createVideo HTTP ${resp.code}: ${raw.take(300)}")
                    return@withContext null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
                val uploadId = o.optString("upload_id").takeUnless { it.isBlank() }
                val ticket = o.optString("ticket").takeUnless { it.isBlank() }
                val ingest = o.optString("ingest_url").takeUnless { it.isBlank() }
                val videoId = o.optString("video_id").takeUnless { it.isBlank() }
                if (uploadId == null || ticket == null || ingest == null || videoId == null) {
                    Log.w(TAG, "createVideo 200 but missing ticket fields: ${raw.take(300)}")
                    return@withContext null
                }
                Ticket(
                    videoId = videoId,
                    uploadId = uploadId,
                    ingestUrl = ingest.trimEnd('/'),
                    ticket = ticket,
                    chunkSize = o.optInt("chunk_size", chunkSize),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "createVideo failed: ${e.message}")
            null
        }
    }

    /** The user's library -> `GET /api/videos` -> {"videos":[...]}. Empty on failure. */
    suspend fun listVideos(apiKey: String): List<Video> = withContext(Dispatchers.IO) {
        try {
            client.newCall(req(apiKey, "/api/videos").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "listVideos HTTP ${resp.code}: ${raw.take(200)}")
                    return@withContext emptyList()
                }
                val arr = runCatching { JSONObject(raw).optJSONArray("videos") }.getOrNull()
                    ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { parse(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listVideos failed: ${e.message}")
            emptyList()
        }
    }

    /** One video + its HLS/thumb urls -> `GET /api/videos/{id}`. Null if missing/failed. */
    suspend fun getVideo(apiKey: String, id: String): Video? = withContext(Dispatchers.IO) {
        try {
            client.newCall(req(apiKey, "/api/videos/$id").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getVideo HTTP ${resp.code}: ${raw.take(200)}")
                    return@withContext null
                }
                parse(runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getVideo failed: ${e.message}")
            null
        }
    }

    /** Count a view (no auth needed). */
    suspend fun reportView(id: String) = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$baseUrl/api/videos/$id/view")
                .post("".toRequestBody(null)).build()).execute().use {}
        }; Unit
    }

    /** Like / unlike -> (liked, likes) or null. */
    suspend fun setLike(apiKey: String, id: String, like: Boolean): Pair<Boolean, Int>? = withContext(Dispatchers.IO) {
        try {
            val rb = req(apiKey, "/api/videos/$id/like")
            val call = if (like) rb.post("".toRequestBody(null)) else rb.delete()
            client.newCall(call.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val o = JSONObject(resp.body?.string().orEmpty())
                o.optBoolean("liked", like) to o.optInt("likes", 0)
            }
        } catch (e: Exception) { null }
    }

    /** Save playback position. */
    suspend fun putProgress(apiKey: String, id: String, posSec: Double, durSec: Double?) = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("position_sec", posSec)
                .apply { if (durSec != null) put("duration_sec", durSec) }
                .toString().toRequestBody(jsonMedia)
            client.newCall(req(apiKey, "/api/videos/$id/progress").put(body).build()).execute().use {}
        }; Unit
    }

    private fun parse(o: JSONObject): Video = Video(
        id = o.optString("id"),
        filename = o.optString("filename").ifBlank { "video.mp4" },
        status = o.optString("status").ifBlank { "unknown" },
        durationSec = o.numOrNull("duration_sec"),
        width = o.intOrNull("width"),
        height = o.intOrNull("height"),
        displayWidth = o.intOrNull("display_width"),
        displayHeight = o.intOrNull("display_height"),
        orientation = o.strOrNull("orientation"),
        aspectRatio = o.strOrNull("aspect_ratio"),
        rotation = o.intOrNull("rotation"),
        fps = o.numOrNull("fps"),
        videoCodec = o.strOrNull("video_codec"),
        audioCodec = o.strOrNull("audio_codec"),
        hasAudio = o.optBoolean("has_audio", false),
        isHdr = o.optBoolean("is_hdr", false),
        spokenLanguage = o.strOrNull("spoken_language"),
        transcriptStatus = o.strOrNull("transcript_status"),
        captionsUrl = abs(o.strOrNull("captions_url")),
        aiTitle = o.strOrNull("ai_title"),
        aiSummary = o.strOrNull("ai_summary"),
        aiTags = strList(o, "ai_tags"),
        aiChapters = chapterList(o, "ai_chapters"),
        enrichStatus = o.strOrNull("enrich_status"),
        viewCount = o.longOrNull("view_count") ?: 0L,
        likes = o.intOrNull("likes") ?: 0,
        liked = o.optBoolean("liked", false),
        progressSec = o.numOrNull("progress_sec"),
        bytes = o.longOrNull("bytes"),
        thumbUrl = abs(o.strOrNull("thumb_url")),
        hlsUrl = abs(o.strOrNull("hls_url")),
        takenAt = o.strOrNull("taken_at"),
        createdAt = o.strOrNull("created_at"),
        error = o.strOrNull("error"),
    )

    /** Spoken-word search -> `GET /api/search?q=`. Empty on failure/blank. */
    suspend fun search(apiKey: String, query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        try {
            val enc = java.net.URLEncoder.encode(q, "UTF-8")
            client.newCall(req(apiKey, "/api/search?q=$enc").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = runCatching { JSONObject(raw).optJSONArray("results") }.getOrNull()
                    ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
                    val m = o.optJSONObject("match")
                    SearchResult(
                        id = o.optString("id"),
                        title = o.optString("title").ifBlank { "video" },
                        language = o.strOrNull("language"),
                        thumbUrl = abs(o.strOrNull("thumb_url")),
                        matchStart = m?.optDouble("start", 0.0) ?: 0.0,
                        matchText = m?.let { it.optString("text").takeUnless { s -> s.isBlank() } },
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    private fun strList(o: JSONObject, k: String): List<String> {
        val arr = if (o.isNull(k)) null else o.optJSONArray(k) ?: return emptyList()
        return (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeUnless { s -> s.isBlank() } }
    }

    private fun chapterList(o: JSONObject, k: String): List<Chapter> {
        val arr = if (o.isNull(k)) null else o.optJSONArray(k) ?: return emptyList()
        return (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            arr?.optJSONObject(i)?.let { Chapter(it.optDouble("start", 0.0), it.optString("title")) }
        }
    }

    private fun JSONObject.strOrNull(k: String): String? =
        if (isNull(k)) null else optString(k).takeUnless { it.isBlank() || it == "null" }

    private fun JSONObject.numOrNull(k: String): Double? =
        if (isNull(k)) null else optDouble(k).takeUnless { it.isNaN() }

    private fun JSONObject.intOrNull(k: String): Int? =
        if (isNull(k)) null else optInt(k, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }

    private fun JSONObject.longOrNull(k: String): Long? =
        if (isNull(k)) null else optLong(k, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }

    companion object {
        private const val TAG = "VideoCloudClient"
    }
}
