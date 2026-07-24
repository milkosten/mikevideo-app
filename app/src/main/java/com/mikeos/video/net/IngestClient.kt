package com.mikeos.video.net

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to **mikevideo-ingest** — the data plane (`up.osmike.com`), a dumb/fast/resilient
 * byte mover authorized by the Bearer **ticket** the control plane minted.
 *
 * The chunk protocol (mirrors mikevideo-cloud/scripts/e2e_test.py, but with `chunk_hash_algo:"md5"`
 * since Android's MessageDigest has md5 and the ingest declares md5 as the Android algo):
 *   POST /ingest/init              {manifest}                -> {missing:[…]}
 *   GET  /ingest/{id}/status                                 -> {missing:[…], complete}
 *   PUT  /ingest/{id}/chunk/{index}  (raw chunk bytes)       -> stored; auto-finalizes on last
 *   POST /ingest/{id}/finalize                               -> {complete}  (or auto on full bitmap)
 *
 * Memory: this client NEVER holds more than one chunk body at a time. The caller
 * ([com.mikeos.video.sync.VideoUploader]) seeks + reads exactly one chunk into a reused buffer
 * and hands us that slice — we never read a whole file.
 *
 * TLS: valid public cert behind Caddy -> standard client + DoH. Long timeouts (slow uploads).
 */
class IngestClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(Doh.dns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val octet = "application/octet-stream".toMediaType()

    data class InitResult(val missing: List<Int>, val resumed: Boolean)

    private fun bearer(ingestUrl: String, path: String, ticket: String): Request.Builder =
        Request.Builder()
            .url("${ingestUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer $ticket")

    /**
     * `POST /ingest/init` with the client-authored manifest. Creates OR resumes the session
     * (idempotent on upload_id/file_hash). Returns which chunk indices ingest still needs, or
     * null on failure. Never throws.
     */
    suspend fun init(ingestUrl: String, ticket: String, manifest: JSONObject): InitResult? {
        val body = manifest.toString().toRequestBody(jsonMedia)
        return try {
            client.newCall(
                bearer(ingestUrl, "/ingest/init", ticket).post(body).build()
            ).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "init HTTP ${resp.code}: ${raw.take(300)}")
                    return null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
                InitResult(missing = intList(o, "missing"), resumed = o.optBoolean("resumed", false))
            }
        } catch (e: Exception) {
            Log.w(TAG, "init failed: ${e.message}")
            null
        }
    }

    /**
     * `PUT /ingest/{id}/chunk/{index}` with the raw chunk bytes (exactly [length] of [buffer]).
     * On the LAST chunk the server auto-finalizes. Returns true on 200 (stored or idempotent
     * no-op). A 422 (hash/length mismatch) returns false so the index stays missing and is retried.
     */
    suspend fun putChunk(
        ingestUrl: String,
        ticket: String,
        uploadId: String,
        index: Int,
        buffer: ByteArray,
        length: Int,
    ): Boolean {
        // RequestBody over the exact [0,length) slice of the reused buffer — no copy of the
        // whole file, only this one chunk is ever in the body.
        val body = buffer.toRequestBody(octet, 0, length)
        return try {
            client.newCall(
                bearer(ingestUrl, "/ingest/$uploadId/chunk/$index", ticket).put(body).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "chunk $index HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return false
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "chunk $index failed: ${e.message}")
            false
        }
    }

    /**
     * `GET /ingest/{id}/status` -> `{missing:[…], received:[…], complete, bytes, total_size}`.
     * The resume + never-trust-200 source of truth: we only mark a video synced when this says
     * `complete=true` with an empty `missing`. Returns null on failure.
     */
    suspend fun status(ingestUrl: String, ticket: String, uploadId: String): Status? {
        return try {
            client.newCall(
                bearer(ingestUrl, "/ingest/$uploadId/status", ticket).get().build()
            ).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "status HTTP ${resp.code}: ${raw.take(200)}")
                    return null
                }
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
                Status(
                    missing = intList(o, "missing"),
                    complete = o.optBoolean("complete", false),
                    bytes = o.optLong("bytes", 0),
                    totalSize = o.optLong("total_size", 0),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "status failed: ${e.message}")
            null
        }
    }

    data class Status(
        val missing: List<Int>,
        val complete: Boolean,
        val bytes: Long,
        val totalSize: Long,
    )

    /** `POST /ingest/{id}/finalize` — explicit finalize (usually auto on the last chunk). */
    suspend fun finalize(ingestUrl: String, ticket: String, uploadId: String): Boolean {
        return try {
            client.newCall(
                bearer(ingestUrl, "/ingest/$uploadId/finalize", ticket)
                    .post(ByteArray(0).toRequestBody(null)).build()
            ).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "finalize HTTP ${resp.code}: ${raw.take(200)}")
                    return false
                }
                runCatching { JSONObject(raw).optBoolean("complete", false) }.getOrDefault(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "finalize failed: ${e.message}")
            false
        }
    }

    private fun intList(o: JSONObject, key: String): List<Int> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optInt(it) }
    }

    companion object {
        private const val TAG = "IngestClient"
    }
}
