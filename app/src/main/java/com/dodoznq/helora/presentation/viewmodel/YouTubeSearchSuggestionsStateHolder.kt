package com.dodoznq.helora.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.dodoznq.helora.data.model.Song
import com.dodoznq.helora.data.youtube.YouTubeMusicRepository
import com.dodoznq.helora.data.youtube.YouTubeSearchSuggestions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Autocomplete for the search box's input field, kept separate from [YouTubeSearchStateHolder]:
 * suggestions are keystroke-driven and short-lived, while a search result is a deliberate
 * submission. Sharing one debounce would mean every keystroke either fires a full search or
 * delays suggestions behind the search's longer debounce.
 *
 * `collectLatest` on the input flow both debounces and cancels the in-flight request on the
 * next keystroke: a superseded request never gets to publish its result.
 */
@Singleton
class YouTubeSearchSuggestionsStateHolder @Inject constructor(
    private val repository: YouTubeMusicRepository,
) {

    @Immutable
    data class State(
        val query: String = "",
        val completions: ImmutableList<String> = persistentListOf(),
        val songs: ImmutableList<Song> = persistentListOf(),
    ) {
        val isEmpty: Boolean get() = completions.isEmpty() && songs.isEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val inputs = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeInputs()
    }

    /** Called on every keystroke; a blank query just clears the dropdown. */
    fun request(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            clear()
            return
        }
        inputs.tryEmit(trimmed)
    }

    fun clear() {
        if (_state.value != State()) {
            _state.value = State()
        }
    }

    fun onCleared() {
        job?.cancel()
        job = null
        scope = null
    }

    @OptIn(FlowPreview::class)
    private fun observeInputs() {
        job?.cancel()
        job = scope?.launch {
            inputs
                .debounce(SUGGESTIONS_DEBOUNCE_MS)
                .collectLatest { query -> execute(query) }
        }
    }

    private suspend fun execute(query: String) {
        val result: YouTubeSearchSuggestions = repository.searchSuggestions(query) ?: return
        _state.value = State(
            query = query,
            completions = result.completions.toImmutableList(),
            songs = result.songs.toImmutableList()
        )
    }

    private companion object {
        const val SUGGESTIONS_DEBOUNCE_MS = 250L
    }
}
