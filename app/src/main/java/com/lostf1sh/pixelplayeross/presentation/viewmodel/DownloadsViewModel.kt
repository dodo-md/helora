package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.database.DownloadedTrackEntity
import com.lostf1sh.pixelplayeross.data.download.MusicDownloadManager
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: MusicDownloadManager,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadedTrackEntity>> = downloadManager.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<Map<String, Float>> = downloadManager.progress

    val wifiOnly: StateFlow<Boolean> = userPreferencesRepository.downloadOverWifiOnlyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDownloadOverWifiOnly(enabled)
            // The constraint is baked into the enqueued work, so the change only takes effect
            // once the queue is re-submitted.
            downloadManager.start()
        }
    }

    /** Deletes the saved file as well as the record. */
    fun delete(videoId: String) {
        viewModelScope.launch { downloadManager.delete(videoId) }
    }

    fun cancel(videoId: String) {
        viewModelScope.launch { downloadManager.cancel(videoId) }
    }

    /** Re-queues a failed download. */
    fun retry(videoId: String) {
        viewModelScope.launch { downloadManager.retry(videoId) }
    }
}
