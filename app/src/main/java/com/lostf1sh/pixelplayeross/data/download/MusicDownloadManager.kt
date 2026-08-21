package com.lostf1sh.pixelplayeross.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lostf1sh.pixelplayeross.data.database.DownloadState
import com.lostf1sh.pixelplayeross.data.database.DownloadedTrackDao
import com.lostf1sh.pixelplayeross.data.database.DownloadedTrackEntity
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.worker.DownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for keeping tracks on the device.
 *
 * Queue state lives in Room so it survives the process dying; byte-level progress stays in
 * memory, because writing a row per chunk would be a lot of database churn for a number that is
 * only ever shown on screen.
 */
@Singleton
class MusicDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadedTrackDao,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Live fraction per video id, 0..1, or -1 when the size is unknown. */
    val progress = _progress.asStateFlow()

    val downloads: Flow<List<DownloadedTrackEntity>> = dao.observeAll()

    /** Video ids that are downloaded or queued, for the "already saved" indicator. */
    val savedVideoIds: Flow<Set<String>> = dao.observeActiveVideoIds().map { it.toSet() }

    suspend fun isDownloaded(videoId: String): Boolean =
        dao.getByVideoId(videoId)?.state == DownloadState.COMPLETED

    /** Queues [songs], skipping anything already saved or in flight. Returns how many were added. */
    suspend fun enqueue(songs: List<Song>): Int {
        val downloadable = songs.filter { it.ytVideoId != null }
        if (downloadable.isEmpty()) return 0

        var queued = 0
        downloadable.forEach { song ->
            val videoId = song.ytVideoId ?: return@forEach
            val existing = dao.getByVideoId(videoId)
            // Re-queueing a failed download is how the user retries it.
            if (existing != null && existing.state != DownloadState.FAILED) return@forEach

            dao.upsert(
                DownloadedTrackEntity(
                    videoId = videoId,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    albumArtUri = song.albumArtUriString,
                    duration = song.duration,
                    state = DownloadState.QUEUED
                )
            )
            queued++
        }

        if (queued > 0) start()
        return queued
    }

    /** Kicks the worker. Safe to call when it is already running — the work is unique. */
    suspend fun start() {
        val wifiOnly = userPreferencesRepository.downloadOverWifiOnlyFlow.first()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
                    .build()
            )
            .build()

        // KEEP, not REPLACE: replacing would cancel a download that is midway through a file.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DownloadWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
        Timber.d("MusicDownloadManager: worker enqueued (wifiOnly=%b)", wifiOnly)
    }

    internal fun publishProgress(videoId: String, fraction: Float) {
        _progress.value = _progress.value + (videoId to fraction)
    }

    internal fun clearProgress(videoId: String) {
        _progress.value = _progress.value - videoId
    }

    /** Removes the download record and the saved file. */
    suspend fun delete(videoId: String) {
        val entry = dao.getByVideoId(videoId) ?: return
        entry.mediaStoreUri?.let { uriString ->
            runCatching {
                context.contentResolver.delete(android.net.Uri.parse(uriString), null, null)
            }.onFailure { Timber.w(it, "Could not delete file for %s", videoId) }
        }
        dao.delete(videoId)
        clearProgress(videoId)
    }

    /** Puts a failed download back in the queue. */
    suspend fun retry(videoId: String) {
        val entry = dao.getByVideoId(videoId) ?: return
        if (entry.state == DownloadState.COMPLETED) return
        dao.updateState(videoId, DownloadState.QUEUED, null)
        start()
    }

    /** Drops a queued entry without touching anything on disk. */
    suspend fun cancel(videoId: String) {
        val entry = dao.getByVideoId(videoId) ?: return
        if (entry.state == DownloadState.COMPLETED) return
        dao.delete(videoId)
        clearProgress(videoId)
    }
}
