package com.mikeos.video.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mikeos.video.net.IngestClient
import com.mikeos.video.net.VideoCloudClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Uploads ONE local camera video to mikevideo-cloud via the chunk protocol, exactly as the
 * proven reference client (mikevideo-cloud/scripts/e2e_test.py) does — but memory-safe on a
 * phone and using `chunk_hash_algo:"md5"` (Android's MessageDigest has md5; ingest declares
 * md5 as the Android algorithm; `file_hash` is ALWAYS sha256).
 *
 * THE MEMORY RULE (1.55 GB reboot-loop incident): the file is NEVER read whole into RAM.
 *  • Manifest pre-pass: seek to each chunk offset, read exactly one chunk into a SINGLE reused
 *    [buf], feed it to a per-chunk md5 AND a running whole-file sha256, repeat.
 *  • Upload: seek+read exactly one chunk into the SAME reused buffer, PUT it, repeat.
 * At most one chunk (≤ chunk_size) is ever resident.
 *
 * The flow (mirrors e2e_test.py step for step):
 *   1. POST /api/videos {filename, content_type, total_size, file_hash:"sha256:…", chunk_size,
 *      count, taken_at}  -> {video_id, upload_id, ingest_url, ticket, chunk_size}
 *   2. build manifest {upload_id, filename, content_type, total_size, chunk_size, count,
 *      chunk_hash_algo:"md5", file_hash:"sha256:…", chunks:{"i":"md5:…"}}
 *   3. POST {ingest}/ingest/init {manifest}         -> {missing:[…]}
 *   4. for each missing i: seek+read chunk i -> PUT {ingest}/ingest/{upload_id}/chunk/{i}
 *      (auto-finalizes on the last chunk). On drop/restart: GET /status -> upload only missing.
 *   5. GET /status; only when complete do we return true (caller marks the ledger synced).
 *
 * We open the video by MediaStore content Uri (scoped storage) via a ParcelFileDescriptor and
 * read through its [FileChannel] (from a FileInputStream), which supports `position()` (seek)
 * without a real filesystem path.
 */
class VideoUploader(
    private val context: Context,
    private val cloud: VideoCloudClient = VideoCloudClient(),
    private val ingest: IngestClient = IngestClient(),
) {
    /** Default chunk size we REQUEST; the cloud echoes/overrides via its response. 4 MiB like e2e. */
    private val requestedChunkSize = 4 * 1024 * 1024

    sealed class Result {
        object Complete : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Upload [item]. If [resumeVideoId]/[resumeUploadId]/[resumeFileHash] are supplied (from the
     * ledger) we RESUME the same ingest session instead of minting a new ticket... but the ticket
     * itself is short-lived, so we always re-mint via POST /api/videos with the SAME file_hash;
     * the cloud/ingest dedupe on upload_id/file_hash so the session (and its received bitmap)
     * survives, and we only PUT what /status still reports missing.
     *
     * Returns [Result.Complete] only after ingest /status confirms complete (never-trust-200).
     */
    suspend fun upload(
        apiKey: String,
        item: CameraVideo,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val total = item.size
        if (total <= 0) return@withContext Result.Failed("empty file")

        // --- Step 2 (pre-pass, streamed): build the manifest without ever holding the whole file.
        val manifestData = runCatching { buildManifest(item, requestedChunkSize) }
            .getOrElse { return@withContext Result.Failed("manifest: ${it.message}") }

        // --- Step 1: reserve the row + mint the ticket (send the sha256 file_hash + count).
        val ticket = cloud.createVideo(
            apiKey = apiKey,
            filename = item.displayName,
            contentType = item.mimeType.ifBlank { "video/mp4" },
            totalSize = total,
            fileHashSha256 = manifestData.fileHash,
            chunkSize = manifestData.chunkSize,
            count = manifestData.count,
            takenAtIso = item.takenAtIso,
        ) ?: return@withContext Result.Failed("POST /api/videos returned no ticket")

        // The cloud may hand back a different chunk_size; if so, rebuild the manifest to match
        // (server validates chunk lengths against manifest.chunk_size).
        val effManifest =
            if (ticket.chunkSize != manifestData.chunkSize) {
                runCatching { buildManifest(item, ticket.chunkSize) }
                    .getOrElse { return@withContext Result.Failed("manifest(rebuild): ${it.message}") }
            } else manifestData

        val manifestJson = effManifest.toJson(ticket.uploadId, item)

        // --- Step 3: init (create or resume the ingest session).
        val initRes = ingest.init(ticket.ingestUrl, ticket.ticket, manifestJson)
            ?: return@withContext Result.Failed("ingest /init failed")

        // Source of truth for what to send: prefer /status (covers resume after a restart).
        var missing = ingest.status(ticket.ingestUrl, ticket.ticket, ticket.uploadId)
            ?.missing ?: initRes.missing
        val totalChunks = effManifest.count
        onProgress?.invoke(totalChunks - missing.size, totalChunks)

        // --- Step 4: seek + read + PUT each missing chunk (one chunk in RAM at a time).
        openChannel(item.uri).use { ch ->
            val buf = ByteArray(effManifest.chunkSize)
            var done = totalChunks - missing.size
            for (i in missing) {
                val len = chunkLen(i, total, effManifest.chunkSize, totalChunks)
                seekReadFully(ch, i.toLong() * effManifest.chunkSize, buf, len)
                val ok = ingest.putChunk(ticket.ingestUrl, ticket.ticket, ticket.uploadId, i, buf, len)
                if (ok) {
                    done++
                    onProgress?.invoke(done, totalChunks)
                }
            }
        }

        // --- Step 5: confirm complete via /status (never-trust-200). Nudge finalize if needed.
        var status = ingest.status(ticket.ingestUrl, ticket.ticket, ticket.uploadId)
        if (status != null && !status.complete && status.missing.isEmpty()) {
            ingest.finalize(ticket.ingestUrl, ticket.ticket, ticket.uploadId)
            status = ingest.status(ticket.ingestUrl, ticket.ticket, ticket.uploadId)
        }
        if (status?.complete == true) {
            // Stash resume/id info on the ledger via the returned ticket (caller writes it).
            item.resolvedVideoId = ticket.videoId
            item.resolvedUploadId = ticket.uploadId
            item.resolvedFileHash = effManifest.fileHash
            Log.i(TAG, "upload complete ${item.displayName} video_id=${ticket.videoId}")
            Result.Complete
        } else {
            item.resolvedVideoId = ticket.videoId
            item.resolvedUploadId = ticket.uploadId
            item.resolvedFileHash = effManifest.fileHash
            Result.Failed("not complete: missing=${status?.missing?.size ?: "?"}")
        }
    }

    // ---- manifest pre-pass (streamed, memory-safe) -----------------------------------------

    private class ManifestData(
        val chunkSize: Int,
        val count: Int,
        val fileHash: String,             // "sha256:…"
        val chunkMd5: Array<String>,      // hex md5 per chunk
    ) {
        fun toJson(uploadId: String, item: CameraVideo): JSONObject {
            val chunks = JSONObject()
            for (i in chunkMd5.indices) chunks.put(i.toString(), "md5:${chunkMd5[i]}")
            return JSONObject()
                .put("upload_id", uploadId)
                .put("filename", item.displayName)
                .put("content_type", item.mimeType.ifBlank { "video/mp4" })
                .put("total_size", item.size)
                .put("chunk_size", chunkSize)
                .put("count", count)
                .put("chunk_hash_algo", "md5")
                .put("file_hash", fileHash)
                .put("chunks", chunks)
        }
    }

    /**
     * Stream the file once: per-chunk md5 + running whole-file sha256, reading exactly one chunk
     * into a single reused buffer. NEVER readBytes() the whole file.
     */
    private fun buildManifest(item: CameraVideo, chunkSize: Int): ManifestData {
        val total = item.size
        val count = ((total + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
        val whole = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        val chunkMd5 = Array(count) { "" }
        val buf = ByteArray(chunkSize)
        openChannel(item.uri).use { ch ->
            for (i in 0 until count) {
                val len = chunkLen(i, total, chunkSize, count)
                seekReadFully(ch, i.toLong() * chunkSize, buf, len)
                md5.reset()
                md5.update(buf, 0, len)
                chunkMd5[i] = md5.digest().toHex()
                whole.update(buf, 0, len)
            }
        }
        return ManifestData(chunkSize, count, "sha256:" + whole.digest().toHex(), chunkMd5)
    }

    // ---- low-level IO (scoped-storage content Uri, seekable via FileChannel) -----------------

    /**
     * A seekable read channel over the MediaStore content Uri. We keep the ParcelFileDescriptor
     * alive with the channel and close both together (closing the channel closes the stream,
     * which closes the pfd — but we hold the pfd explicitly for clarity/safety).
     */
    private fun openChannel(uri: Uri): SeekableChannel {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("cannot open $uri")
        val fis = FileInputStream(pfd.fileDescriptor)
        return SeekableChannel(fis.channel, fis, pfd)
    }

    /** Seek to [offset] and read exactly [len] bytes into [buf][0,len). */
    private fun seekReadFully(ch: SeekableChannel, offset: Long, buf: ByteArray, len: Int) {
        ch.channel.position(offset)
        val bb = ByteBuffer.wrap(buf, 0, len)
        var read = 0
        while (read < len) {
            val n = ch.channel.read(bb)
            if (n < 0) throw IllegalStateException("EOF at offset ${offset + read} (wanted $len)")
            read += n
        }
    }

    private fun chunkLen(index: Int, total: Long, chunkSize: Int, count: Int): Int =
        if (index < count - 1) chunkSize else (total - (count - 1).toLong() * chunkSize).toInt()

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "VideoUploader"
        private val HEX = "0123456789abcdef".toCharArray()
    }
}

/**
 * A [FileChannel] over a content-Uri file descriptor that supports `position()` (seek) without a
 * real filesystem path. Closing it closes the backing stream + ParcelFileDescriptor.
 */
private class SeekableChannel(
    val channel: FileChannel,
    private val fis: FileInputStream,
    private val pfd: android.os.ParcelFileDescriptor,
) : java.io.Closeable {
    override fun close() {
        try { channel.close() } catch (_: Exception) {}
        try { fis.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}
