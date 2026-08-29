package com.dodoznq.helora.presentation.screens

import androidx.compose.runtime.Immutable

@Immutable
internal data class LibrarySongPlaybackUiState(
    val isCurrentSong: Boolean = false,
    val isPlaying: Boolean = false
)
