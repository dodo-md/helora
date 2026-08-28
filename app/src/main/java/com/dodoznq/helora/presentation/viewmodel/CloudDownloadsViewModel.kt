package com.dodoznq.helora.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dodoznq.helora.data.offline.CloudOfflineRepository
import com.dodoznq.helora.data.offline.OfflineDownload
import com.dodoznq.helora.data.offline.OfflineDownloadStatus
import com.dodoznq.helora.data.model.Song
import com.dodoznq.helora.data.preferences.UserPreferencesRepository
import com.dodoznq.helora.data.stream.StreamCacheConfig
import com.dodoznq.helora.data.stream.StreamCacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class CloudDownloadsUiState(
    val completed: ImmutableList<OfflineDownload> = persistentListOf(),
    val active: ImmutableList<OfflineDownload> = persistentListOf(),
    val failed: ImmutableList<OfflineDownload> = persistentListOf(),
    val usedBytes: Long = 0L,
    val streamCacheUsedBytes: Long = 0L,
    val streamCacheMaxBytes: Long = StreamCacheConfig.DEFAULT_MAX_BYTES
) {
    val totalCount: Int get() = completed.size + active.size + failed.size
}

internal fun List<OfflineDownload>.toCloudDownloadsUiState(): CloudDownloadsUiState =
    CloudDownloadsUiState(
        completed = filter { it.status == OfflineDownloadStatus.COMPLETE }.toImmutableList(),
        active = filter {
            it.status == OfflineDownloadStatus.QUEUED ||
                it.status == OfflineDownloadStatus.DOWNLOADING
        }.toImmutableList(),
        failed = filter { it.status == OfflineDownloadStatus.FAILED }.toImmutableList(),
        usedBytes = asSequence()
            .filter {
                it.status == OfflineDownloadStatus.COMPLETE ||
                    it.status == OfflineDownloadStatus.DOWNLOADING
            }
            .sumOf { it.bytesDownloaded.coerceAtLeast(0L) }
    )

@HiltViewModel
class CloudDownloadsViewModel @Inject constructor(
    private val repository: CloudOfflineRepository,
    private val streamCacheManager: StreamCacheManager,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {
    private val streamCacheUsedBytes = MutableStateFlow(0L)

    val uiState: StateFlow<CloudDownloadsUiState> = combine(
        repository.observeAll().map(List<OfflineDownload>::toCloudDownloadsUiState),
        userPreferencesRepository.streamCacheMaxBytesFlow,
        streamCacheUsedBytes
    ) { downloadsState, maxBytes, usedBytes ->
        downloadsState.copy(streamCacheMaxBytes = maxBytes, streamCacheUsedBytes = usedBytes)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CloudDownloadsUiState()
    )

    init {
        refreshStreamCacheUsedBytes()
    }

    fun refreshStreamCacheUsedBytes() {
        viewModelScope.launch { streamCacheUsedBytes.value = streamCacheManager.currentSizeBytes() }
    }

    fun setStreamCacheMaxBytes(bytes: Long) {
        viewModelScope.launch { userPreferencesRepository.setStreamCacheMaxBytes(bytes) }
    }

    fun clearStreamCache() {
        viewModelScope.launch {
            streamCacheManager.clear()
            streamCacheUsedBytes.value = 0L
        }
    }

    fun remove(download: OfflineDownload) {
        viewModelScope.launch { repository.remove(download.sourceUri) }
    }

    fun retry(download: OfflineDownload) {
        if (download.status != OfflineDownloadStatus.FAILED) return
        viewModelScope.launch { repository.retry(download.sourceUri) }
    }

    fun downloadSelected(songs: List<Song>) {
        val cloudSongs = songs
            .filter(CloudOfflineRepository::isCloudSong)
            .distinctBy(Song::contentUriString)
        if (cloudSongs.isEmpty()) return
        viewModelScope.launch { repository.enqueueAll(cloudSongs) }
    }
}
