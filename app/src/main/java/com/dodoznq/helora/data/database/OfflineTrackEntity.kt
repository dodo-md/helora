package com.dodoznq.helora.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent state for an app-private copy of a cloud track.
 *
 * [sourceUri] remains the canonical identity (for example `navidrome://abc`). The player can
 * therefore resolve an existing queue item to [localPath] without rewriting the library row.
 */
@Entity(
    tableName = "offline_tracks",
    indices = [
        Index(value = ["source_uri"], unique = true),
        Index(value = ["song_id"]),
        Index(value = ["state"])
    ]
)
data class OfflineTrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "download_id") val downloadId: String,
    /** Ownership token that prevents a superseded worker from updating a newer attempt. */
    @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "source_uri") val sourceUri: String,
    val provider: String,
    val title: String,
    /**
     * Carried so a download can be published outside the app's own directory, where the file
     * needs a real folder and embedded tags rather than an opaque attempt filename.
     */
    val artist: String? = null,
    val album: String? = null,
    @ColumnInfo(name = "album_art_uri") val albumArtUri: String? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "local_path") val localPath: String?,
    /**
     * Set only for downloads published through MediaStore, which is how YouTube tracks land in
     * the shared Music folder. Deleting or reading those goes through the resolver rather than
     * [localPath], since the app does not own that path directly.
     */
    @ColumnInfo(name = "media_store_uri") val mediaStoreUri: String? = null,
    val state: String,
    @ColumnInfo(name = "bytes_downloaded") val bytesDownloaded: Long = 0L,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null
)
