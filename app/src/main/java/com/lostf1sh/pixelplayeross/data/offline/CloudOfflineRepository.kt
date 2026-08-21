package com.lostf1sh.pixelplayeross.data.offline

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.lostf1sh.pixelplayeross.data.database.OfflineTrackDao
import com.lostf1sh.pixelplayeross.data.database.OfflineTrackEntity
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.worker.CloudTrackDownloadWorker
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class OfflineDownloadStatus(val storageValue: String) {
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    COMPLETE("complete"),
    FAILED("failed");

    companion object {
        fun fromStorage(value: String): OfflineDownloadStatus =
            entries.firstOrNull { it.storageValue == value } ?: FAILED
    }
}

data class OfflineDownload(
    val downloadId: String,
    val sourceUri: String,
    val status: OfflineDownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val localPath: String?,
    val errorMessage: String?,
    val title: String = "",
    val provider: String = "",
    val mediaStoreUri: String? = null
) {
    val progress: Float?
        get() = totalBytes?.takeIf { it > 0L }
            ?.let { (bytesDownloaded.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

@Singleton
class CloudOfflineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: OfflineTrackDao,
    private val workManager: WorkManager
) {
    private val mutationMutex = Mutex()

    fun observe(song: Song): Flow<OfflineDownload?> = observe(song.contentUriString)

    fun observe(sourceUri: String): Flow<OfflineDownload?> =
        dao.observeBySourceUri(sourceUri).map { it?.toModel() }

    fun observeCompleted(): Flow<List<OfflineDownload>> =
        dao.observeCompleted().map { rows -> rows.map(OfflineTrackEntity::toModel) }

    fun observeAll(): Flow<List<OfflineDownload>> =
        dao.observeAll().map { rows -> rows.map(OfflineTrackEntity::toModel) }

    suspend fun enqueue(song: Song) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val provider = providerFor(song.contentUriString) ?: return@withLock
            val downloadId = downloadId(song.contentUriString)
            val existing = dao.getBySourceUri(song.contentUriString)
            if (existing?.state == OfflineDownloadStatus.COMPLETE.storageValue &&
                isStillOnDisk(existing)
            ) {
                return@withLock
            }
            existing?.let(::discardArtifacts)

            val attemptId = UUID.randomUUID().toString()
            val request = downloadRequest(
                downloadId = downloadId,
                attemptId = attemptId,
                sourceUri = song.contentUriString
            )

            val now = System.currentTimeMillis()
            dao.upsert(
                OfflineTrackEntity(
                    downloadId = downloadId,
                    attemptId = attemptId,
                    songId = song.id,
                    sourceUri = song.contentUriString,
                    provider = provider,
                    title = song.title,
                    artist = song.artist.takeIf { it.isNotBlank() },
                    album = song.album.takeIf { it.isNotBlank() },
                    albumArtUri = song.albumArtUriString,
                    mimeType = song.mimeType,
                    localPath = null,
                    state = OfflineDownloadStatus.QUEUED.storageValue,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )

            workManager.enqueueUniqueWork(
                workName(downloadId),
                ExistingWorkPolicy.REPLACE,
                request
            ).await()
        }
    }

    suspend fun enqueueAll(songs: Collection<Song>) {
        songs.asSequence()
            .filter { isCloudSong(it) }
            .distinctBy { it.contentUriString }
            .forEach { enqueue(it) }
    }

    suspend fun retry(sourceUri: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val existing = dao.getBySourceUri(sourceUri) ?: return@withLock
            if (existing.state != OfflineDownloadStatus.FAILED.storageValue) return@withLock

            discardArtifacts(existing)

            val attemptId = UUID.randomUUID().toString()
            val request = downloadRequest(
                downloadId = existing.downloadId,
                attemptId = attemptId,
                sourceUri = sourceUri
            )
            dao.upsert(
                existing.copy(
                    attemptId = attemptId,
                    localPath = null,
                    state = OfflineDownloadStatus.QUEUED.storageValue,
                    bytesDownloaded = 0L,
                    totalBytes = null,
                    updatedAt = System.currentTimeMillis(),
                    errorMessage = null
                )
            )
            workManager.enqueueUniqueWork(
                workName(existing.downloadId),
                ExistingWorkPolicy.REPLACE,
                request
            ).await()
        }
    }

    suspend fun remove(song: Song) = remove(song.contentUriString)

    suspend fun remove(sourceUri: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val entity = dao.getBySourceUri(sourceUri) ?: return@withLock
            // Remove ownership first. Any in-flight worker update after this point is a no-op.
            if (dao.deleteBySourceUriForAttempt(sourceUri, entity.attemptId) == 0) {
                return@withLock
            }

            try {
                workManager.cancelUniqueWork(workName(entity.downloadId)).await()
            } finally {
                discardArtifacts(entity)
            }
        }
    }

    /** Called on ExoPlayer's loading thread; Room I/O is dispatched by the caller. */
    suspend fun resolveLocalUri(sourceUri: String): Uri? = withContext(Dispatchers.IO) {
        val entity = dao.getBySourceUri(sourceUri) ?: return@withContext null
        if (entity.state != OfflineDownloadStatus.COMPLETE.storageValue) return@withContext null

        // A published download lives in the shared Music folder, where the raw path is only
        // readable with the audio permission. The content URI belongs to this app either way,
        // so it keeps working for someone who only ever uses YouTube.
        entity.mediaStoreUri?.let { uriString ->
            val uri = uriString.toUri()
            return@withContext if (mediaStoreEntryExists(uri)) {
                uri
            } else {
                // Deleted from under us by a file manager or another app.
                dao.deleteBySourceUri(sourceUri)
                null
            }
        }

        val file = entity.localPath?.let(::File)
        if (file?.isFile == true && file.length() > 0L) {
            Uri.fromFile(file)
        } else {
            dao.deleteBySourceUri(sourceUri)
            null
        }
    }

    /**
     * Whether a completed row still has its file. Published downloads are checked through the
     * resolver: their [OfflineTrackEntity.localPath] points into the shared Music folder, which
     * reads as missing without the audio permission and would make every enqueue re-download.
     */
    private fun isStillOnDisk(entity: OfflineTrackEntity): Boolean =
        entity.mediaStoreUri?.let { mediaStoreEntryExists(it.toUri()) }
            ?: (entity.localPath?.let(::File)?.isFile == true)

    private fun mediaStoreEntryExists(uri: Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length != 0L } == true
    }.getOrDefault(false)

    /**
     * Clears everything an attempt may have left behind. Published downloads need the resolver:
     * they sit in the shared Music folder, so deleting the path directly would silently fail
     * and leave the file on the device after the user removed it.
     */
    private fun discardArtifacts(entity: OfflineTrackEntity) {
        entity.mediaStoreUri?.let { uriString ->
            runCatching { context.contentResolver.delete(uriString.toUri(), null, null) }
        }
        entity.localPath?.let(::File)?.takeIf(File::exists)?.delete()
        deleteAttemptFiles(context, entity.downloadId, entity.attemptId)
    }

    companion object {
        fun isCloudSong(song: Song): Boolean = providerFor(song.contentUriString) != null

        const val PROVIDER_YOUTUBE = "youtube"

        fun providerFor(sourceUri: String): String? = when (sourceUri.substringBefore(':', "").lowercase()) {
            "navidrome" -> "navidrome"
            "jellyfin" -> "jellyfin"
            // Everything a YouTube track needs from this queue is already here; the only thing
            // that differs is where the finished file is published.
            YouTubeMusicRepository.URI_SCHEME -> PROVIDER_YOUTUBE
            else -> null
        }

        fun downloadId(sourceUri: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(sourceUri.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun workName(downloadId: String): String = "cloud_track_download_$downloadId"

        internal fun attemptFileStem(downloadId: String, attemptId: String): String =
            "$downloadId.$attemptId"

        internal fun deleteAttemptFiles(context: Context, downloadId: String, attemptId: String) {
            val prefix = "${attemptFileStem(downloadId, attemptId)}."
            downloadDirectory(context).listFiles()
                ?.asSequence()
                ?.filter { it.name.startsWith(prefix) }
                ?.forEach(File::delete)
        }

        fun downloadDirectory(context: Context): File =
            File(context.filesDir, "cloud_downloads").apply { mkdirs() }
    }

    private fun downloadRequest(
        downloadId: String,
        attemptId: String,
        sourceUri: String
    ) = OneTimeWorkRequestBuilder<CloudTrackDownloadWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()
        )
        .setInputData(
            workDataOf(
                CloudTrackDownloadWorker.KEY_DOWNLOAD_ID to downloadId,
                CloudTrackDownloadWorker.KEY_ATTEMPT_ID to attemptId,
                CloudTrackDownloadWorker.KEY_SOURCE_URI to sourceUri
            )
        )
        .addTag(CloudTrackDownloadWorker.TAG)
        .addTag(workName(downloadId))
        .build()
}

private fun OfflineTrackEntity.toModel() = OfflineDownload(
    downloadId = downloadId,
    sourceUri = sourceUri,
    status = OfflineDownloadStatus.fromStorage(state),
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    localPath = localPath,
    errorMessage = errorMessage,
    title = title,
    provider = provider,
    mediaStoreUri = mediaStoreUri
)
