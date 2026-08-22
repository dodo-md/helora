package com.dodoznq.helora.presentation.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dodoznq.helora.R
import com.dodoznq.helora.data.model.Song
import com.dodoznq.helora.presentation.navigation.Screen
import com.dodoznq.helora.presentation.navigation.navigateSafelyReplacing
import com.dodoznq.helora.presentation.screens.SearchResultAlbumItem
import com.dodoznq.helora.presentation.screens.SearchResultArtistItem
import com.dodoznq.helora.presentation.screens.SearchResultSectionHeader
import com.dodoznq.helora.presentation.components.subcomps.EnhancedSongListItem
import com.dodoznq.helora.presentation.viewmodel.PlayerViewModel
import com.dodoznq.helora.presentation.viewmodel.YouTubeSearchStateHolder

/**
 * Renders the YouTube Music portion of the search results, appended below the local sections.
 *
 * Item keys are namespaced so they cannot collide with local results: a YouTube track and a
 * saved copy of the same track share a song id by design, and duplicate keys crash LazyColumn.
 */
fun LazyListScope.youTubeSearchSections(
    state: YouTubeSearchStateHolder.State,
    playerViewModel: PlayerViewModel,
    navController: NavHostController,
    currentPlayingSongId: String?,
    isPlaying: Boolean,
    onSongMoreOptionsClick: (Song) -> Unit,
    onItemSelected: () -> Unit
) {
    if (state.isIdle) return

    if (state.error != null) {
        item(key = "yt_error") {
            YouTubeSearchStatusRow(
                message = stringResource(
                    when (state.error) {
                        YouTubeSearchStateHolder.Error.OFFLINE -> R.string.search_youtube_offline
                        else -> R.string.search_youtube_error
                    }
                ),
                onRetry = { playerViewModel.retryYouTubeSearch() }
            )
        }
        return
    }

    if (state.isLoading && state.isEmpty) {
        item(key = "yt_loading") { YouTubeSearchLoadingRow() }
        return
    }

    if (state.songs.isNotEmpty()) {
        item(key = "yt_header_songs") {
            SearchResultSectionHeader(title = stringResource(R.string.search_youtube_songs))
        }
        items(
            count = state.songs.size,
            key = { index -> "yt_song_${state.songs[index].id}" },
            contentType = { "yt_search_song" }
        ) { index ->
            val song = state.songs[index]
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                EnhancedSongListItem(
                    song = song,
                    isPlaying = isPlaying,
                    isCurrentSong = currentPlayingSongId == song.id,
                    onMoreOptionsClick = onSongMoreOptionsClick,
                    onClick = {
                        // Only this track goes into the queue. Queueing the rest of the results
                        // would mean a search for "snap" plays every other song called "snap"
                        // before the station ever gets a turn.
                        playerViewModel.playYouTubeSong(song)
                        onItemSelected()
                    }
                )
            }
        }
    }

    if (state.albums.isNotEmpty()) {
        item(key = "yt_header_albums") {
            SearchResultSectionHeader(title = stringResource(R.string.search_youtube_albums))
        }
        items(
            count = state.albums.size,
            key = { index -> "yt_album_${state.albums[index].id}" },
            contentType = { "yt_search_album" }
        ) { index ->
            val album = state.albums[index]
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                SearchResultAlbumItem(
                    album = album,
                    onPlayClick = {
                        playerViewModel.playYouTubeAlbum(album)
                        onItemSelected()
                    },
                    onOpenClick = {
                        navController.navigateSafelyReplacing(
                            route = Screen.AlbumDetail.createRoute(album),
                            patternToPop = Screen.AlbumDetail.route
                        )
                        onItemSelected()
                    }
                )
            }
        }
    }

    if (state.artists.isNotEmpty()) {
        item(key = "yt_header_artists") {
            SearchResultSectionHeader(title = stringResource(R.string.search_youtube_artists))
        }
        items(
            count = state.artists.size,
            key = { index -> "yt_artist_${state.artists[index].id}" },
            contentType = { "yt_search_artist" }
        ) { index ->
            val artist = state.artists[index]
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                SearchResultArtistItem(
                    artist = artist,
                    onPlayClick = {
                        playerViewModel.playYouTubeArtist(artist)
                        onItemSelected()
                    },
                    onOpenClick = {
                        navController.navigateSafelyReplacing(
                            route = Screen.ArtistDetail.createRoute(artist),
                            patternToPop = Screen.ArtistDetail.route
                        )
                        onItemSelected()
                    }
                )
            }
        }
    }

    // Results already showing while a newer query is still in flight.
    if (state.isLoading) {
        item(key = "yt_loading_more") { YouTubeSearchLoadingRow() }
    }
}

@Composable
private fun YouTubeSearchLoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(2.dp))
        Text(
            text = stringResource(R.string.search_filter_youtube_music),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun YouTubeSearchStatusRow(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(R.string.search_youtube_retry))
        }
    }
}
