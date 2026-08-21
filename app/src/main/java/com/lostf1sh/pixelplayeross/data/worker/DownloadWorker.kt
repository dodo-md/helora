package com.lostf1sh.pixelplayeross.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.database.DownloadState
import com.lostf1sh.pixelplayeross.data.database.DownloadedTrackDao
import com.lostf1sh.pixelplayeross.data.database.DownloadedTrackEntity
import com.lostf1sh.pixelplayeross.data.download.AudioDownloader
import com.lostf1sh.pixelplayeross.data.download.MusicDownloadManager
import com.lostf1sh.pixelplayeross.data.model.Song
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Drains the download queue one track at a time.
 *
 * Sequential on purpose: parallel downloads would compete for bandwidth with playback, and
 * YouTube rate-limits aggressive clients. The queue lives in Room, so the worker simply keeps
 * asking for the next queued row until there is none.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: DownloadedTrackDao,
    private val downloader: AudioDownloader,
    private val downloadManager: MusicDownloadManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Anything still marked RUNNING belongs to a previous, killed process. Downloads are
        // not resumable, so those go back to the front of the queue.
        dao.requeueInterrupted()

        var completed = 0
        var failed = 0

        while (true) {
            val next = dao.getQueued().firstOrNull() ?: break
            setForeground(buildForegroundInfo(next.title, 0f))
            dao.updateState(next.videoId, DownloadState.RUNNING)

            try {
                val result = downloader.download(next.toSong()) { fraction ->
                    downloadManager.publishProgress(next.videoId, fraction)
                    setForeground(buildForegroundInfo(next.title, fraction))
                }

                result.fold(
                    onSuccess = { downloaded ->
                        dao.markCompleted(
                            videoId = next.videoId,
                            uri = downloaded.uri.toString(),
                            filePath = downloaded.filePath,
                            totalBytes = downloaded.totalBytes
                        )
                        completed++
                    },
                    onFailure = { error ->
                        dao.updateState(
                            next.videoId,
                            DownloadState.FAILED,
                            error.message ?: error::class.simpleName
                        )
                        failed++
                    }
                )
            } catch (e: CancellationException) {
                // The row goes back to QUEUED so the next run picks it up rather than losing it.
                dao.updateState(next.videoId, DownloadState.QUEUED)
                downloadManager.clearProgress(next.videoId)
                throw e
            } catch (e: Exception) {
                Timber.w(e, "DownloadWorker: unexpected failure for %s", next.videoId)
                dao.updateState(next.videoId, DownloadState.FAILED, e.message)
                failed++
            } finally {
                downloadManager.clearProgress(next.videoId)
            }
        }

        Timber.d("DownloadWorker: finished (completed=%d failed=%d)", completed, failed)
        // Failures are recorded per row and retried by the user, so the run itself succeeded.
        return Result.success()
    }

    private fun DownloadedTrackEntity.toSong(): Song = Song(
        id = videoId,
        title = title,
        artist = artist,
        artistId = -1L,
        album = album,
        albumId = -1L,
        path = "",
        contentUriString = "ytmusic://$videoId",
        albumArtUriString = albumArtUri,
        duration = duration,
        mimeType = null,
        bitrate = null,
        sampleRate = null,
        ytVideoId = videoId
    )

    private fun buildForegroundInfo(title: String, fraction: Float): ForegroundInfo {
        createChannelIfNeeded()

        val indeterminate = fraction < 0f
        val percent = (fraction * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createChannelIfNeeded() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val WORK_NAME = "pixelplay_download_queue"
        private const val CHANNEL_ID = "pixelplay_downloads"
        private const val NOTIFICATION_ID = 4711
    }
}
