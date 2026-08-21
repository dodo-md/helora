package com.lostf1sh.pixelplayeross.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Where a finished download ended up. */
data class DownloadResult(
    val uri: Uri,
    val filePath: String?,
    val totalBytes: Long
)

/**
 * Saves a YouTube track into shared storage as a normal music file.
 *
 * The file is created through MediaStore with `IS_PENDING = 1`, filled, tagged, and only then
 * published. Until it is published nothing else on the device can see a half-written file, and
 * the media scanner will not index a truncated track if the download dies partway.
 */
@Singleton
class AudioDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: YouTubeMusicRepository,
    baseClient: OkHttpClient
) {

    private val client: OkHttpClient = baseClient.newBuilder()
        // A whole track over a slow link takes far longer than the shared 8s read timeout.
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * @param onProgress fraction in 0..1, or -1 when the server sends no content length.
     */
    suspend fun download(
        song: Song,
        onProgress: suspend (Float) -> Unit
    ): Result<DownloadResult> = withContext(Dispatchers.IO) {
        val videoId = song.ytVideoId
            ?: return@withContext Result.failure(IllegalArgumentException("Not a YouTube track"))

        val stream = repository.getDownloadableStream(videoId)
            ?: return@withContext Result.failure(IOException("No downloadable audio stream"))

        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, DownloadPaths.fileName(song.title, song.trackNumber, stream.extension))
            put(MediaStore.Audio.Media.RELATIVE_PATH, DownloadPaths.relativePath(song.artist, song.album))
            put(MediaStore.Audio.Media.MIME_TYPE, stream.mimeType)
            put(MediaStore.Audio.Media.TITLE, song.title)
            put(MediaStore.Audio.Media.ARTIST, song.artist)
            put(MediaStore.Audio.Media.ALBUM, song.album)
            if (song.duration > 0) put(MediaStore.Audio.Media.DURATION, song.duration)
            if (song.trackNumber > 0) put(MediaStore.Audio.Media.TRACK, song.trackNumber)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: return@withContext Result.failure(IOException("MediaStore refused the insert"))

        try {
            val totalBytes = writeAudio(uri, stream.url, onProgress)
            coroutineContext.ensureActive()
            // Tags must be written before publishing: clearing IS_PENDING triggers the media
            // scanner, and whatever it reads from the file at that moment is what every other
            // app will show.
            writeTags(uri, song)

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null
            )
            Result.success(DownloadResult(uri, resolveFilePath(uri), totalBytes))
        } catch (e: CancellationException) {
            // Leaving a pending row behind would occupy the filename forever.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            Timber.w(e, "Download failed for %s", videoId)
            Result.failure(e)
        }
    }

    private suspend fun writeAudio(
        uri: Uri,
        streamUrl: String,
        onProgress: suspend (Float) -> Unit
    ): Long {
        val request = Request.Builder().url(streamUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Upstream returned ${response.code}")
            }
            val body = response.body
            val expectedBytes = body.contentLength()
            var written = 0L
            var lastReported = 0f

            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read

                        if (expectedBytes > 0) {
                            val fraction = written.toFloat() / expectedBytes
                            // Reporting every chunk would hammer the notification and the DB.
                            if (fraction - lastReported >= PROGRESS_REPORT_STEP) {
                                lastReported = fraction
                                onProgress(fraction.coerceIn(0f, 1f))
                            }
                        } else if (lastReported == 0f) {
                            lastReported = 1f
                            onProgress(INDETERMINATE_PROGRESS)
                        }
                    }
                    output.flush()
                }
            } ?: throw IOException("Could not open the destination for writing")

            onProgress(1f)
            return written
        }
    }

    /**
     * Embeds tags and cover art. Best-effort: a track that plays but shows a bare filename in
     * another app is still a successful download, so failures here are logged, not fatal.
     */
    private suspend fun writeTags(uri: Uri, song: Song) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val properties = hashMapOf(
                    "TITLE" to arrayOf(song.title),
                    "ARTIST" to arrayOf(song.artist),
                    "ALBUM" to arrayOf(song.album),
                )
                song.albumArtist?.takeIf { it.isNotBlank() }
                    ?.let { properties["ALBUMARTIST"] = arrayOf(it) }
                song.genre?.takeIf { it.isNotBlank() }
                    ?.let { properties["GENRE"] = arrayOf(it) }
                if (song.trackNumber > 0) {
                    properties["TRACKNUMBER"] = arrayOf(song.trackNumber.toString())
                }
                if (song.year > 0) properties["DATE"] = arrayOf(song.year.toString())

                TagLib.savePropertyMap(descriptor.dup().detachFd(), properties)
            }
        }.onFailure { Timber.w(it, "Tag write failed for %s", song.title) }

        val artwork = song.albumArtUriString ?: return
        runCatching {
            val bytes = fetchArtwork(artwork) ?: return
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                TagLib.savePictures(
                    descriptor.dup().detachFd(),
                    arrayOf(
                        Picture(
                            data = bytes,
                            description = "Cover",
                            pictureType = "Front Cover",
                            mimeType = "image/jpeg"
                        )
                    )
                )
            }
        }.onFailure { Timber.w(it, "Cover art write failed for %s", song.title) }
    }

    private fun fetchArtwork(url: String): ByteArray? {
        if (!url.startsWith("http")) return null
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body.bytes().takeIf { it.size in 1..MAX_ARTWORK_BYTES }
            }
        }.getOrNull()
    }

    /** The on-disk path, so the row the library scan creates can be matched to this download. */
    private fun resolveFilePath(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_REPORT_STEP = 0.02f
        const val INDETERMINATE_PROGRESS = -1f
        const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
    }
}
