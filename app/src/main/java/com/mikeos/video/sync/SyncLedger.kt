package com.mikeos.video.sync

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The upload ledger — the app's memory of which local camera videos are already safe on
 * mikevideo-cloud, so nothing is ever re-uploaded.
 *
 * The key is `(mediastore_id + size + date_modified)` (see [SyncEntry.ledgerKey]): if Mike
 * trims/edits a clip its size or mtime changes, so it re-syncs; an untouched file keeps the
 * same key and is skipped forever. `synced=true` is only written after the ingest `/status`
 * confirms `complete` (never-trust-200) — so a partial upload resumes rather than being
 * treated as done.
 *
 * We also stash the server `upload_id` + `file_hash` so an interrupted upload can resume the
 * SAME ingest session (POST /ingest/init with the same upload_id/file_hash re-attaches).
 */
@Entity(tableName = "sync_ledger")
data class SyncEntry(
    /** `${mediaStoreId}:${size}:${dateModified}` — stable across app restarts, changes on edit. */
    @PrimaryKey @ColumnInfo(name = "ledger_key") val ledgerKey: String,
    @ColumnInfo(name = "mediastore_id") val mediaStoreId: Long,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    /** Server video row id (uuid) once POST /api/videos succeeded; null before. */
    @ColumnInfo(name = "video_id") val videoId: String? = null,
    /** Ingest session id (hex) for resume; null before POST /api/videos. */
    @ColumnInfo(name = "upload_id") val uploadId: String? = null,
    /** "sha256:…" whole-file hash — matches the server session so a resume re-attaches. */
    @ColumnInfo(name = "file_hash") val fileHash: String? = null,
    /** true only after ingest /status reported complete (all chunks in + finalized). */
    @ColumnInfo(name = "synced") val synced: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun ledgerKey(mediaStoreId: Long, size: Long, dateModified: Long): String =
            "$mediaStoreId:$size:$dateModified"
    }
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_ledger WHERE ledger_key = :key LIMIT 1")
    suspend fun byKey(key: String): SyncEntry?

    @Query("SELECT * FROM sync_ledger ORDER BY updated_at DESC")
    suspend fun all(): List<SyncEntry>

    @Query("SELECT COUNT(*) FROM sync_ledger WHERE synced = 1")
    suspend fun syncedCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SyncEntry)
}

@Database(entities = [SyncEntry::class], version = 1, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun dao(): SyncDao

    companion object {
        @Volatile private var instance: SyncDatabase? = null
        fun get(context: Context): SyncDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "mikevideo_sync.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
