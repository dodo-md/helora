package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.SearchFilterType
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.youtube.RemoteTrackCache
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeStreamProxy
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube Music search, kept deliberately separate from [SearchStateHolder].
 *
 * Local search is a Room + FTS query that re-emits on database changes and treats any exception
 * as "clear the results". Folding a network call into that flow would mean a YouTube timeout
 * wiping the user's local results, and a slow extractor delaying them. So this holder owns its
 * own state, its own failure modes, and a longer debounce — local search stays untouched.
 */
@Singleton
class YouTubeSearchStateHolder @Inject constructor(
    private val repository: YouTubeMusicRepository,
    private val streamProxy: YouTubeStreamProxy,
    private val remoteTrackCache: RemoteTrackCache,
    private val connectivityStateHolder: ConnectivityStateHolder,
) {

    enum class Error { OFFLINE, NETWORK, EXTRACTOR }

    @Immutable
    data class State(
        val query: String = "",
        val isLoading: Boolean = false,
        val songs: ImmutableList<Song> = persistentListOf(),
        val albums: ImmutableList<Album> = persistentListOf(),
        val artists: ImmutableList<Artist> = persistentListOf(),
        val error: Error? = null,
    ) {
        val isEmpty: Boolean
            get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()

        /** True when the section has nothing at all to draw and should stay collapsed. */
        val isIdle: Boolean
            get() = isEmpty && !isLoading && error == null
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val requests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val latestRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null
    private var lastQuery: String = ""

    private data class SearchRequest(val query: String, val requestId: Long)

    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeRequests()
    }

    /**
     * Only issues a request when the YouTube section is actually visible; otherwise the state is
     * cleared so a stale result cannot appear when the user switches filters back.
     */
    fun performSearch(query: String, filter: SearchFilterType) {
        if (filter != SearchFilterType.ALL && filter != SearchFilterType.YOUTUBE) {
            clear()
            return
        }
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            clear()
            return
        }
        lastQuery = trimmed
        val requestId = latestRequestId.incrementAndGet()
        _state.value = _state.value.copy(query = trimmed, isLoading = true, error = null)
        requests.tryEmit(SearchRequest(trimmed, requestId))
    }

    fun retry() {
        val query = lastQuery
        if (query.isNotBlank()) {
            performSearch(query, SearchFilterType.ALL)
        }
    }

    fun clear() {
        latestRequestId.incrementAndGet()
        lastQuery = ""
        if (_state.value != State()) {
            _state.value = State()
        }
    }

    fun onCleared() {
        searchJob?.cancel()
        searchJob = null
        scope = null
    }

    @OptIn(FlowPreview::class)
    private fun observeRequests() {
        searchJob?.cancel()
        searchJob = scope?.launch {
            requests
                // Longer than local search's 300ms: every request here is a network round trip,
                // and YouTube rate-limits rapid repeated extraction.
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { request -> execute(request) }
        }
    }

    private suspend fun execute(request: SearchRequest) {
        if (!connectivityStateHolder.isOnline.value) {
            publish(request) { it.copy(isLoading = false, error = Error.OFFLINE) }
            return
        }

        try {
            val result = repository.search(request.query)
            remoteTrackCache.putAll(result.songs)
            warmUpLikelyPlayback(result.songs)

            publish(request) {
                it.copy(
                    isLoading = false,
                    songs = result.songs.toImmutableList(),
                    albums = result.albums.toImmutableList(),
                    artists = result.artists.toImmutableList(),
                    // The repository already isolates each section, so a wholly empty result
                    // after a successful call is "no matches", not a failure.
                    error = null
                )
            }
        } catch (_: CancellationException) {
            // Superseded by a newer query.
        } catch (e: Exception) {
            Timber.w(e, "YouTube search failed for '%s'", request.query)
            val error = if (e is IOException) Error.NETWORK else Error.EXTRACTOR
            publish(request) { it.copy(isLoading = false, error = error) }
        }
    }

    /**
     * Resolving a YouTube stream costs a couple of seconds, and that cost would otherwise land
     * entirely after the user taps. Starting the proxy and extracting the top result while they
     * are still reading the list hides most of it, at the price of one extra request per search.
     */
    private fun warmUpLikelyPlayback(songs: List<Song>) {
        streamProxy.startIfNeeded()
        songs.firstOrNull()?.ytVideoId?.let(repository::prefetchStream)
    }

    /** Drops the update if a newer query has been issued in the meantime. */
    private fun publish(request: SearchRequest, transform: (State) -> State) {
        if (request.requestId != latestRequestId.get()) return
        val updated = transform(_state.value).copy(query = request.query)
        if (_state.value != updated) {
            _state.value = updated
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 600L
    }
}
