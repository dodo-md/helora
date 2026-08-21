package com.lostf1sh.pixelplayeross.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.lostf1sh.pixelplayeross.data.database.OfflineTrackEntity
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeArtworkUrls
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where a published download ended up. */
data class PublishedDownload(
    val uri: Uri,
    val filePath: String?
)

/**
 * Publishes a finished download into the shared Music folder as an ordinary music file.
 *
 * The app's own directory is the right home for a cached copy of a self-hosted track, but a
 * YouTube download is the only copy the user has. Putting it in `Music/Helora/…` through
 * MediaStore means their other players and file manager can see it, it survives uninstalling
 * the app, and Helora's own library scan picks it up as a normal local song.
 *
 * The row is created with `IS_PENDING = 1`, filled, tagged, and only then published: clearing
 * that flag triggers the media scanner, and whatever it reads at that moment is what every
 * other app will show from then on.
 */
@Singleton
class MediaStoreDownloadPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    baseClient: OkHttpClient
) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Moves [source] into the shared Music folder. The caller keeps ownership of [source] and is
     * responsible for deleting it; this only reads from it.
     *
     * [genre] is written into the file rather than into the MediaStore row, because the scanner
     * derives the row's genre from the tags and would overwrite anything set here directly.
     */
    fun publish(
        entity: OfflineTrackEntity,
        source: File,
        extension: String,
        mimeType: String,
        genre: String? = null
    ): PublishedDownload {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(
                MediaStore.Audio.Media.DISPLAY_NAME,
                DownloadPaths.fileName(entity.title, trackNumber = 0, extension = extension)
            )
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                DownloadPaths.relativePath(entity.artist.orEmpty(), entity.album.orEmpty())
            )
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.TITLE, entity.title)
            entity.artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
            entity.album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused the insert")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open the destination for writing")

            writeTags(uri, entity, genre)

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null
            )

            val filePath = resolveFilePath(uri)
            // The library only takes rows whose DURATION clears the minimum song length, and
            // duration is read-only: MediaStore fills it by reading the file. Publishing alone
            // does not always get that far, and a row with no duration is invisible in Songs
            // and in search. Scanning explicitly is what SongMetadataEditor does after it
            // rewrites tags, for the same reason.
            filePath?.let(::forceMediaRescan)
            return PublishedDownload(uri, filePath)
        } catch (e: Throwable) {
            // A pending row nobody publishes would hold on to the filename forever.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /**
     * Best effort: a track that plays but shows a bare filename elsewhere is still a successful
     * download, so failures here are logged rather than failing the whole thing.
     */
    private fun writeTags(uri: Uri, entity: OfflineTrackEntity, genre: String?) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val properties = hashMapOf("TITLE" to arrayOf(entity.title))
                entity.artist?.takeIf { it.isNotBlank() }
                    ?.let {
                        properties["ARTIST"] = arrayOf(it)
                        properties["ALBUMARTIST"] = arrayOf(it)
                    }
                entity.album?.takeIf { it.isNotBlank() }
                    ?.let { properties["ALBUM"] = arrayOf(it) }
                // Left out entirely when unknown. An absent tag lets the library fall back to
                // its own placeholder, while writing one would make a guess look like a fact
                // in every other player too.
                genre?.takeIf { it.isNotBlank() }
                    ?.let { properties["GENRE"] = arrayOf(it) }

                TagLib.savePropertyMap(descriptor.dup().detachFd(), properties)
            }
        }.onFailure { Timber.w(it, "Tag write failed for %s", entity.title) }

        // The thumbnail on the queue row is a 120x120 search result; what goes into the file is
        // permanent and read by other players, so ask for a large one.
        val artworkUrl = YouTubeArtworkUrls.withSize(entity.albumArtUri, EMBEDDED_ARTWORK_SIZE_PX)
            ?: return
        runCatching {
            val bytes = fetchArtwork(artworkUrl) ?: return
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
        }.onFailure { Timber.w(it, "Cover art write failed for %s", entity.title) }
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

    /**
     * Makes MediaStore read the finished file, which is what fills in duration and the rest of
     * the audio metadata. Also nudges the content observer, so the library picks the track up
     * without waiting for the next full sync.
     */
    private fun forceMediaRescan(filePath: String) {
        runCatching {
            if (!File(filePath).exists()) return
            MediaScannerConnection.scanFile(context, arrayOf(filePath), null) { path, uri ->
                Timber.tag(TAG).d("Published download indexed: %s -> %s", path, uri)
            }
        }.onFailure { Timber.tag(TAG).w(it, "Could not rescan %s", filePath) }
    }

    /** The on-disk path, so the library scan's row can be matched back to this download. */
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
        const val TAG = "DownloadPublisher"

        /**
         * Roughly 100-450 KB depending on the source image. Larger is available, but this rides
         * along in every file of a downloaded album, so it is not free.
         */
        const val EMBEDDED_ARTWORK_SIZE_PX = 1000
        const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
    }
}
