package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedTrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: DownloadedTrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(track: DownloadedTrackEntity): Long

    @Query("SELECT * FROM downloaded_tracks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DownloadedTrackEntity>>

    /** Video ids that are downloaded or on their way, for the "already saved" indicator. */
    @Query("SELECT video_id FROM downloaded_tracks WHERE state != ${DownloadState.FAILED}")
    fun observeActiveVideoIds(): Flow<List<String>>

    @Query("SELECT * FROM downloaded_tracks WHERE video_id = :videoId")
    suspend fun getByVideoId(videoId: String): DownloadedTrackEntity?

    @Query("SELECT * FROM downloaded_tracks WHERE state = ${DownloadState.QUEUED} ORDER BY created_at ASC")
    suspend fun getQueued(): List<DownloadedTrackEntity>

    @Query("UPDATE downloaded_tracks SET state = :state, error_message = :error WHERE video_id = :videoId")
    suspend fun updateState(videoId: String, state: Int, error: String? = null)

    @Query(
        """
        UPDATE downloaded_tracks
        SET state = ${DownloadState.COMPLETED},
            media_store_uri = :uri,
            file_path = :filePath,
            total_bytes = :totalBytes,
            completed_at = :completedAt,
            error_message = NULL
        WHERE video_id = :videoId
        """
    )
    suspend fun markCompleted(
        videoId: String,
        uri: String,
        filePath: String?,
        totalBytes: Long,
        completedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM downloaded_tracks WHERE video_id = :videoId")
    suspend fun delete(videoId: String)

    /**
     * Anything left mid-flight when the process died. Downloads are not resumable, so these are
     * requeued rather than resumed.
     */
    @Query("UPDATE downloaded_tracks SET state = ${DownloadState.QUEUED} WHERE state = ${DownloadState.RUNNING}")
    suspend fun requeueInterrupted()
}
