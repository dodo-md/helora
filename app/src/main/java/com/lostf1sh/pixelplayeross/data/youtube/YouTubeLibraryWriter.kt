package com.lostf1sh.pixelplayeross.data.youtube

import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SourceType
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.DeezerGenreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gives a YouTube track a row in the library.
 *
 * Search results are deliberately ephemeral, but three things only work for tracks the database
 * knows about: the liked list joins `favorites` against `songs`, listening stats resolve names
 * through `songs` (a missing row shows as "Unknown"), and the daily mix is built from library
 * songs. So a track earns a row once the user actually listens to it or saves it.
 *
 * [YouTubeIds] hands out deterministic ids for the song, its album and its artist, which is what
 * makes this an idempotent upsert: a favourite written before the row existed already points at
 * the id this creates. The album and artist rows are not optional — `songs` has foreign keys on
 * both, so inserting a song without them fails.
 *
 * Downloaded tracks are not written here. Those are published into the shared Music folder and
 * the ordinary media scan picks them up as local songs, which is the point of downloading.
 *
 * A row written here has no genre. YouTube does not publish one, and the placeholder that used
 * to stand in put every track the user had ever played into a single "YouTube Music" bucket,
 * which also meant a single player theme colour. Looking a real one up is opt-in through
 * [UserPreferencesRepository.youTubeGenreLookupEnabledFlow], because a track that was merely
 * streamed is not a file the user keeps and the lookup costs a request per new album.
 */
@Singleton
class YouTubeLibraryWriter @Inject constructor(
    private val musicDao: MusicDao,
    private val remoteTrackCache: RemoteTrackCache,
    private val deezerGenreRepository: DeezerGenreRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) {

    /** Resolves [songId] through the in-flight cache; a no-op for anything not from YouTube. */
    suspend fun promoteById(songId: String) {
        val song = remoteTrackCache.get(songId) ?: return
        promote(song)
    }

    suspend fun promote(song: Song) = withContext(Dispatchers.IO) {
        val videoId = song.ytVideoId ?: return@withContext
        if (song.contentUriString.substringBefore(':') != YouTubeMusicRepository.URI_SCHEME) {
            return@withContext
        }

        runCatching {
            val artistName = song.artist.ifBlank { UNKNOWN_ARTIST }
            val albumName = song.album.ifBlank { YouTubeMusicRepository.DEFAULT_ALBUM_NAME }
            val genre = resolveGenre(song)

            // Order matters: songs has foreign keys onto both of these.
            musicDao.insertArtistsIgnoreConflicts(
                listOf(
                    ArtistEntity(
                        id = song.artistId,
                        name = artistName,
                        trackCount = 0
                    )
                )
            )
            musicDao.insertAlbumsIgnoreConflicts(
                listOf(
                    AlbumEntity(
                        id = song.albumId,
                        title = albumName,
                        artistName = artistName,
                        artistId = song.artistId,
                        albumArtUriString = song.albumArtUriString,
                        songCount = 0,
                        dateAdded = System.currentTimeMillis(),
                        year = song.year
                    )
                )
            )
            musicDao.insertSongsIgnoreConflicts(
                listOf(
                    SongEntity(
                        id = YouTubeIds.songId(videoId),
                        title = song.title,
                        artistName = artistName,
                        artistId = song.artistId,
                        albumArtist = song.albumArtist,
                        albumArtistId = song.artistId,
                        albumName = albumName,
                        albumId = song.albumId,
                        contentUriString = song.contentUriString,
                        albumArtUriString = song.albumArtUriString,
                        duration = song.duration,
                        genre = genre,
                        filePath = "",
                        parentDirectoryPath = PARENT_DIRECTORY,
                        isFavorite = false,
                        lyrics = null,
                        trackNumber = song.trackNumber,
                        year = song.year,
                        dateAdded = System.currentTimeMillis(),
                        mimeType = song.mimeType,
                        bitrate = song.bitrate,
                        sampleRate = song.sampleRate,
                        sourceType = SourceType.YOUTUBE_MUSIC
                    )
                )
            )

            // Insert ignores conflicts, so a track already in the library would keep its empty
            // genre forever. Written separately, and only where there is none.
            if (genre != null) {
                musicDao.fillMissingGenre(YouTubeIds.songId(videoId), genre)
            }
        }.onFailure { Timber.w(it, "Could not add %s to the library", song.title) }
    }

    /**
     * The track's own genre if it somehow has one, otherwise Deezer, otherwise nothing.
     *
     * Never throws and never blocks the write: the lookup answers null on any failure, and a
     * row with no genre is the normal outcome rather than an error.
     */
    private suspend fun resolveGenre(song: Song): String? {
        song.genre?.takeIf { it.isNotBlank() }?.let { return it }
        if (!userPreferencesRepository.youTubeGenreLookupEnabledFlow.first()) return null
        return deezerGenreRepository.genreFor(song.artist, song.title)
    }

    private companion object {
        const val UNKNOWN_ARTIST = "Unknown Artist"

        /**
         * A marker, not a real path. Directory rules skip negative ids anyway, which is every id
         * [YouTubeIds] produces, so nothing filters on this.
         */
        const val PARENT_DIRECTORY = "YouTube Music"
    }
}
