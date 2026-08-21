package com.lostf1sh.pixelplayeross.data.repository

import com.lostf1sh.pixelplayeross.data.network.deezer.DeezerApiService
import com.lostf1sh.pixelplayeross.utils.NetworkRetryUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Names of artists similar to a given one, from Deezer.
 *
 * This exists for the radio. A station is a YouTube mix, and when the mix runs dry the only
 * thing left to play was more of the same artist, which is not what "more like this" means.
 * Deezer publishes a similar-artists list per artist and the app already talks to it, so the
 * station can carry on with acts the user would plausibly want next.
 *
 * Two calls: find the artist, then read their related list. Cached per artist name, because a
 * station asks once and then lives off the answer.
 */
@Singleton
class DeezerRelatedArtistsRepository @Inject constructor(
    private val deezerApiService: DeezerApiService
) {

    private val cache = object : LinkedHashMap<String, List<String>>(0, LOAD_FACTOR, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<String>>
        ): Boolean = size > CACHE_SIZE
    }
    private val cacheMutex = Mutex()

    /**
     * Artists similar to [artistName], most similar first, or empty when Deezer has no answer.
     *
     * Never throws. The radio treats an empty list as "this step has nothing", which is exactly
     * how it behaved before this step existed.
     */
    suspend fun relatedArtists(artistName: String?): List<String> {
        val wanted = artistName?.trim().orEmpty()
        if (wanted.isEmpty()) return emptyList()
        val key = DeezerArtistMatching.normalize(wanted)
        if (key.isEmpty()) return emptyList()

        cacheMutex.withLock { cache[key] }?.let { return it }

        val related = try {
            fetch(wanted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Related artists lookup failed for %s", wanted)
            // Deliberately not cached: unlike an artist Deezer genuinely does not know, a
            // failure here says nothing about the artist and is worth asking again.
            return emptyList()
        }

        cacheMutex.withLock { cache[key] = related }
        return related
    }

    private suspend fun fetch(artistName: String): List<String> {
        val candidates = withRetry("deezerSearchArtist") {
            deezerApiService.searchArtist(artistName, limit = ARTIST_SEARCH_LIMIT).data
        }
        // Searching by name is enough and beats resolving the artist through one of their
        // tracks: both agree wherever both work, and this one still answers for an artist
        // whose individual songs are missing from Deezer.
        val artist = DeezerArtistMatching.bestMatch(artistName, candidates) ?: return emptyList()

        val related = withRetry("deezerRelatedArtists") {
            deezerApiService.getRelatedArtists(artist.id).data
        }
        val seed = DeezerArtistMatching.normalize(artistName)
        return related
            .map { it.name.trim() }
            .filter { it.isNotEmpty() && DeezerArtistMatching.normalize(it) != seed }
            .distinctBy { DeezerArtistMatching.normalize(it) }
    }

    private suspend fun <T> withRetry(name: String, block: suspend () -> T): T =
        NetworkRetryUtils.withNetworkRetry(
            operationName = name,
            maxAttempts = NETWORK_RETRY_ATTEMPTS,
            initialDelayMs = NETWORK_RETRY_INITIAL_DELAY_MS,
            onRetry = { attempt, max, throwable ->
                Timber.tag(TAG).d(throwable, "%s retry %d/%d", name, attempt, max)
            },
            block = block
        )

    private companion object {
        const val TAG = "DeezerRelatedArtists"
        const val CACHE_SIZE = 50
        const val LOAD_FACTOR = 0.75f

        /**
         * Deep enough to reach past the near-empty duplicate entries Deezer leads with. One
         * result is not: the first "Sezen Aksu" has 15 fans and no related artists at all.
         */
        const val ARTIST_SEARCH_LIMIT = 10
        const val NETWORK_RETRY_ATTEMPTS = 3
        const val NETWORK_RETRY_INITIAL_DELAY_MS = 500L
    }
}
