package com.dodoznq.helora.presentation.viewmodel

import com.dodoznq.helora.presentation.navigation.RemoteDetailId
import com.dodoznq.helora.data.youtube.RemoteTrackCache
import com.dodoznq.helora.data.youtube.YouTubeMusicRepository
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dodoznq.helora.data.model.Album
import com.dodoznq.helora.data.model.Song
import com.dodoznq.helora.data.repository.MusicRepository
import com.dodoznq.helora.data.offline.CloudOfflineRepository
import com.dodoznq.helora.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val album: Album? = null,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val cloudOfflineRepository: CloudOfflineRepository,
    private val youTubeMusicRepository: YouTubeMusicRepository,
    private val remoteTrackCache: RemoteTrackCache,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()
    val completedOfflineUris: StateFlow<Set<String>> = cloudOfflineRepository.observeCompleted()
        .map { downloads -> downloads.mapTo(mutableSetOf()) { it.sourceUri } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var loadedAlbumId: Long? = null

    init {
        val albumIdString: String? = savedStateHandle.get("albumId")
        val youTubeBrowseId = RemoteDetailId.youTubeIdOrNull(albumIdString)
        if (youTubeBrowseId != null) {
            // Remote albums ride the same route behind a prefixed id, so this screen serves both.
            loadYouTubeAlbum(youTubeBrowseId)
        } else if (albumIdString != null) {
            val albumId = albumIdString.toLongOrNull()
            if (albumId != null) {
                loadedAlbumId = albumId
                loadAlbumData(albumId)
            } else {
                _uiState.update { it.copy(error = context.getString(R.string.invalid_album_id), isLoading = false) }
            }
        } else {
            _uiState.update { it.copy(error = context.getString(R.string.album_id_not_found), isLoading = false) }
        }
    }

    private fun loadYouTubeAlbum(browseId: String) {

        viewModelScope.launch {

            _uiState.update { it.copy(isLoading = true, error = null) }

            val detail = runCatching { youTubeMusicRepository.getAlbum(browseId) }.getOrNull()

            if (detail == null || detail.songs.isEmpty()) {

                _uiState.update { it.copy(error = context.getString(R.string.album_not_found), isLoading = false) }

                return@launch

            }

            // Cached so the tracks stay resolvable once handed to the player, which cannot

            // look them up in the library.

            remoteTrackCache.putAll(detail.songs)

            detail.songs.firstOrNull()?.ytVideoId?.let(youTubeMusicRepository::prefetchStream)

            _uiState.update {

                it.copy(album = detail.album, songs = detail.songs, isLoading = false, error = null)

            }

        }

    }


    private fun loadAlbumData(id: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val albumDetailsFlow = musicRepository.getAlbumById(id)
                val albumSongsFlow = musicRepository.getSongsForAlbum(id)

                combine(albumDetailsFlow, albumSongsFlow) { album, songs ->
                    if (album != null) {
                        AlbumDetailUiState(
                            album = album,
                            songs = songs.sortedWith(
                                compareBy<Song> { it.discNumber ?: 1 }
                                    .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                                    .thenBy { it.title.lowercase() }
                            ),
                            isLoading = false
                        )
                    } else {
                        AlbumDetailUiState(
                            error = context.getString(R.string.album_not_found),
                            isLoading = false
                        )
                    }
                }
                    .catch { e ->
                        emit(
                            AlbumDetailUiState(
                                error = context.getString(R.string.error_loading_album, e.localizedMessage ?: ""),
                                isLoading = false
                            )
                        )
                    }
                    .collect { newState ->
                        _uiState.value = newState
                    }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = context.getString(R.string.error_loading_album, e.localizedMessage ?: ""),
                        isLoading = false
                    )
                }
            }
        }
    }

    /** Re-attempts loading the album after a failure (wired to the error-state retry button). */
    fun retry() {
        loadedAlbumId?.let { loadAlbumData(it) }
    }

    fun update(songs: List<Song>) {
        _uiState.update {
            it.copy(
                isLoading = false,
                songs = songs
            )
        }
    }

    fun downloadAlbum(songs: List<Song>) {
        viewModelScope.launch { cloudOfflineRepository.enqueueAll(songs) }
    }

    fun removeAlbumDownloads(songs: List<Song>) {
        viewModelScope.launch {
            songs.filter(CloudOfflineRepository::isCloudSong)
                .forEach { cloudOfflineRepository.remove(it) }
        }
    }
}
