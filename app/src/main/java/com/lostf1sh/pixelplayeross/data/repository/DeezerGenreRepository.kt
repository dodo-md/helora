package com.lostf1sh.pixelplayeross.data.repository

import com.lostf1sh.pixelplayeross.data.network.deezer.DeezerApiService
import com.lostf1sh.pixelplayeross.data.network.deezer.DeezerTrack
import com.lostf1sh.pixelplayeross.utils.NetworkRetryUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a track's genre through Deezer.
 *
 * YouTube does not publish one. Its Topic uploads carry `category = "Music"` and nothing finer,
 * so a downloaded track used to land in the library under the placeholder genre and every
 * YouTube track collapsed into a single bucket. Deezer has a genre on every album and an open
 * API, and the app already talks to it for artist artwork.
 *
 * The genre sits on the album rather than the track, so a lookup is two calls: find the track,
 * then read its album. Album results are cached, which matters because downloading an album
 * asks the same question once per track.
 *
 * ### Why the match is verified
 *
 * A loose search always returns something. Asking Deezer for "Zeynep Bastık Aman" answers with
 * a different song by the same artist, and writing that song's genre into the file would be
 * worse than writing nothing, because a wrong genre is invisible once it is in the tag. So the
 * strict field query runs first, a loose query is only a fallback, and either way the candidate
 * has to match on both artist and title before it is believed.
 */
@Singleton
class DeezerGenreRepository @Inject constructor(
    private val deezerApiService: DeezerApiService
) {

    private val albumGenres = object : LinkedHashMap<Long, String>(0, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>): Boolean =
            size > CACHE_SIZE
    }
    private val cacheMutex = Mutex()

    /**
     * The genre for [title] by [artist], or null when Deezer has no confident answer.
     *
     * Never throws. A genre is a nicety on top of a download that already succeeded, so a
     * network failure here leaves the tag unwritten rather than failing the download.
     */
    suspend fun genreFor(artist: String?, title: String?): String? {
        val wantedArtist = artist?.trim().orEmpty()
        val wantedTitle = title?.trim().orEmpty()
        if (wantedArtist.isEmpty() || wantedTitle.isEmpty()) return null

        return try {
            val match = findTrack(wantedArtist, wantedTitle) ?: return null
            val albumId = match.album?.id?.takeIf { it > 0 } ?: return null
            genreForAlbum(albumId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Genre lookup failed for %s - %s", wantedArtist, wantedTitle)
            null
        }
    }

    /** Strict field query first, then a loose one. Both are filtered through [matches]. */
    private suspend fun findTrack(artist: String, title: String): DeezerTrack? {
        val strict = search("artist:\"$artist\" track:\"$title\"", STRICT_LIMIT)
        strict.firstOrNull { matches(artist, title, it) }?.let { return it }

        val loose = search("$artist $title", LOOSE_LIMIT)
        return loose.firstOrNull { matches(artist, title, it) }
    }

    private suspend fun search(query: String, limit: Int): List<DeezerTrack> =
        NetworkRetryUtils.withNetworkRetry(
            operationName = "deezerSearchTrack",
            maxAttempts = NETWORK_RETRY_ATTEMPTS,
            initialDelayMs = NETWORK_RETRY_INITIAL_DELAY_MS,
            onRetry = { attempt, max, throwable ->
                Timber.tag(TAG).d(throwable, "Track search retry %d/%d", attempt, max)
            }
        ) {
            deezerApiService.searchTrack(query, limit).data
        }

    private suspend fun genreForAlbum(albumId: Long): String? {
        cacheMutex.withLock { albumGenres[albumId] }?.let { return it.ifEmpty { null } }

        val album = NetworkRetryUtils.withNetworkRetry(
            operationName = "deezerAlbum",
            maxAttempts = NETWORK_RETRY_ATTEMPTS,
            initialDelayMs = NETWORK_RETRY_INITIAL_DELAY_MS,
            onRetry = { attempt, max, throwable ->
                Timber.tag(TAG).d(throwable, "Album fetch retry %d/%d", attempt, max)
            }
        ) {
            deezerApiService.getAlbum(albumId)
        }

        val entries = album.genres?.data.orEmpty().filter { it.name.isNotBlank() }
        // genre_id names the album's primary genre; the list is ordered only loosely, so an
        // album tagged with ten genres would otherwise be filed under whichever came first.
        val genre = entries.firstOrNull { it.id == album.genreId }?.name
            ?: entries.firstOrNull()?.name

        // The empty string records "asked, and Deezer had none", so an album with no genre is
        // not looked up once per track.
        cacheMutex.withLock { albumGenres[albumId] = genre.orEmpty() }
        return genre
    }

    companion object {
        private const val TAG = "DeezerGenreRepository"
        private const val CACHE_SIZE = 100
        private const val LOAD_FACTOR = 0.75f
        private const val STRICT_LIMIT = 3
        private const val LOOSE_LIMIT = 5
        private const val NETWORK_RETRY_ATTEMPTS = 3
        private const val NETWORK_RETRY_INITIAL_DELAY_MS = 500L

        /**
         * Accepts a candidate only when both halves line up.
         *
         * The artist may be a superset, because Deezer credits featured artists in the name
         * where YouTube often does not. The title has to be equal once decorations are gone,
         * which is what keeps a different song by the right artist from being accepted.
         */
        internal fun matches(artist: String, title: String, candidate: DeezerTrack): Boolean {
            val wantedTitle = DeezerArtistMatching.normalize(title)
            val candidateTitle = DeezerArtistMatching.normalize(candidate.title)
            if (wantedTitle.isEmpty() || candidateTitle.isEmpty()) return false
            if (wantedTitle != candidateTitle) return false

            return DeezerArtistMatching.artistMatches(
                DeezerArtistMatching.normalize(artist),
                DeezerArtistMatching.normalize(candidate.artist?.name.orEmpty())
            )
        }

        internal fun normalize(value: String): String = DeezerArtistMatching.normalize(value)
    }
}
