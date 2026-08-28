package com.dodoznq.helora.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.dodoznq.helora.data.model.SearchFilterType
import com.dodoznq.helora.data.search.UnifiedSearchMerger
import com.dodoznq.helora.data.search.UnifiedSearchRow
import com.dodoznq.helora.di.DispatcherProvider
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Presents [SearchStateHolder] and [YouTubeSearchStateHolder] as one merged row list.
 *
 * This holder only consumes the other two — it never touches their internals, so a YouTube
 * failure still can't wipe local results. See [YouTubeSearchStateHolder]'s KDoc for why they
 * stay separate underneath.
 */
@Singleton
class UnifiedSearchStateHolder @Inject constructor(
    private val searchStateHolder: SearchStateHolder,
    private val youTubeSearchStateHolder: YouTubeSearchStateHolder,
    private val dispatcherProvider: DispatcherProvider,
) {

    @Immutable
    data class UnifiedSearchState(
        val rows: ImmutableList<UnifiedSearchRow> = persistentListOf(),
        val isYouTubeLoading: Boolean = false,
        val youTubeError: YouTubeSearchStateHolder.Error? = null,
    )

    private val idleState = MutableStateFlow(UnifiedSearchState()).asStateFlow()
    private var mergedState: StateFlow<UnifiedSearchState>? = null
    private var scope: CoroutineScope? = null

    val state: StateFlow<UnifiedSearchState>
        get() = mergedState ?: idleState

    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        mergedState = combine(
            searchStateHolder.searchResults,
            youTubeSearchStateHolder.state
        ) { localResults, youTubeState ->
            UnifiedSearchState(
                rows = UnifiedSearchMerger.merge(localResults, youTubeState.songs),
                isYouTubeLoading = youTubeState.isLoading,
                youTubeError = youTubeState.error
            )
        }
            .flowOn(dispatcherProvider.default)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), UnifiedSearchState())
    }

    /** Single entry point: triggers both underlying searches, each keeping its own debounce. */
    fun performSearch(query: String, filter: SearchFilterType) {
        searchStateHolder.performSearch(query)
        youTubeSearchStateHolder.performSearch(query, filter)
    }

    fun onCleared() {
        mergedState = null
        scope = null
    }
}
