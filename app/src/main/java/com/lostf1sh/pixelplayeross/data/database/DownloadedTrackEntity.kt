package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle of one download. */
object DownloadState {
    const val QUEUED = 0
    const val RUNNING = 1
    const val COMPLETED = 2
    const val FAILED = 3
}

/**
 * A YouTube track the user asked to keep on the device.
 *
 * The audio itself lives in shared storage and is picked up by the ordinary MediaStore scan,
 * so it becomes a normal library song. This table is the *link* back to the YouTube track it
 * came from — without it there is no way to show "already downloaded" next to a search result,
 * or to play the saved file instead of streaming it again.
 */
@Entity(
    tableName = "downloaded_tracks",
    indices = [Index(value = ["state"]), Index(value = ["media_store_uri"])]
)
data class DownloadedTrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "video_id") val videoId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "album") val album: String,
    @ColumnInfo(name = "album_art_uri") val albumArtUri: String? = null,
    @ColumnInfo(name = "duration") val duration: Long = 0L,
    /** Content URI of the finished file; null until the download completes. */
    @ColumnInfo(name = "media_store_uri") val mediaStoreUri: String? = null,
    /** Absolute path, for matching the row the library scan creates for the same file. */
    @ColumnInfo(name = "file_path") val filePath: String? = null,
    @ColumnInfo(name = "state") val state: Int = DownloadState.QUEUED,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long = 0L,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
)
