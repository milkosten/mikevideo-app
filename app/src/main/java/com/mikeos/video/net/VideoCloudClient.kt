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

    /** A video row from the library (`GET /api/videos`) / detail (`GET /api/videos/{id}`). */
    data class Video(
        val id: String,
        val filename: String,
        val status: String,               // uploading | encoding | ready | failed
        val durationSec: Double?,
        val width: Int?,
        val height: Int?,
        val bytes: Long?,
        val thumbUrl: String?,            // absolute (prefixed) or null
        val hlsUrl: String?,              // absolute (prefixed) or null (detail only, when ready)
        val takenAt: String?,
        val createdAt: String?,
        val error: String?,
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

    private fun parse(o: JSONObject): Video = Video(
        id = o.optString("id"),
        filename = o.optString("filename").ifBlank { "video.mp4" },
        status = o.optString("status").ifBlank { "unknown" },
        durationSec = o.numOrNull("duration_sec"),
        width = o.intOrNull("width"),
        height = o.intOrNull("height"),
        bytes = o.longOrNull("bytes"),
        thumbUrl = abs(o.strOrNull("thumb_url")),
        hlsUrl = abs(o.strOrNull("hls_url")),
        takenAt = o.strOrNull("taken_at"),
        createdAt = o.strOrNull("created_at"),
        error = o.strOrNull("error"),
    )

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
