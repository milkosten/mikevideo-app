package com.mikeos.video.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.time.Instant

/** One local camera video discovered via MediaStore. */
data class CameraVideo(
    val mediaStoreId: Long,
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val mimeType: String,
    /** MediaStore DATE_MODIFIED (seconds) — part of the ledger key so an edit re-syncs. */
    val dateModified: Long,
    /** DATE_TAKEN (ms) or null; used for the ISO-8601 taken_at sent to the cloud. */
    val dateTakenMillis: Long?,
) {
    val ledgerKey: String get() = SyncEntry.ledgerKey(mediaStoreId, size, dateModified)

    /** taken_at as ISO-8601 (house rule: clouds want ISO-8601 strings, never epoch Longs). */
    val takenAtIso: String?
        get() = dateTakenMillis?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }

    // Filled in by VideoUploader on a successful (or partial) upload, for the ledger.
    @JvmField var resolvedVideoId: String? = null
    @JvmField var resolvedUploadId: String? = null
    @JvmField var resolvedFileHash: String? = null
}

/**
 * Queries MediaStore for the phone's camera videos, newest first.
 *
 * We do NOT read any bytes here — only metadata (id, size, dates, mime, name). The actual file
 * is streamed one chunk at a time by [VideoUploader], honouring the memory rule.
 */
object CameraVideos {
    private const val TAG = "CameraVideos"

    // Guardrail: skip absurdly large clips inline. The chunk protocol handles big files fine
    // (bounded RAM), but a sanity ceiling avoids pathological cases; tune as needed. 8 GB.
    private const val MAX_BYTES = 8L * 1024 * 1024 * 1024

    fun query(context: Context, limit: Int = 500): List<CameraVideo> {
        val out = ArrayList<CameraVideo>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_TAKEN,
        )
        // Newest first by capture/modified date.
        val sort = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        try {
            context.contentResolver.query(collection, projection, null, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val modCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(idCol)
                    val size = c.getLong(sizeCol)
                    if (size <= 0 || size > MAX_BYTES) continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    out.add(
                        CameraVideo(
                            mediaStoreId = id,
                            uri = uri,
                            displayName = c.getString(nameCol) ?: "video_$id.mp4",
                            size = size,
                            mimeType = c.getString(mimeCol) ?: "video/mp4",
                            dateModified = c.getLong(modCol),
                            dateTakenMillis = c.getLong(takenCol).takeIf { it > 0 },
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed: ${e.message}")
        }
        return out
    }
}
