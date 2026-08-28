package com.dodoznq.helora.presentation.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dodoznq.helora.R
import com.dodoznq.helora.data.model.SearchResultItem
import com.dodoznq.helora.data.model.Song
import com.dodoznq.helora.data.search.UnifiedSearchRow
import com.dodoznq.helora.presentation.components.MiniPlayerHeight
import com.dodoznq.helora.presentation.components.subcomps.EnhancedSongListItem
import com.dodoznq.helora.presentation.viewmodel.YouTubeSearchStateHolder
import com.dodoznq.helora.utils.formatSongCount
import kotlinx.collections.immutable.ImmutableList

/**
 * Flat, deduplicated local+YouTube search results. Purely presentational: every action is
 * forwarded to the caller, which owns the actual playback/navigation side effects.
 */
@Composable
fun UnifiedResultsList(
    rows: ImmutableList<UnifiedSearchRow>,
    isYouTubeLoading: Boolean,
    youTubeError: YouTubeSearchStateHolder.Error?,
    onLocalClick: (SearchResultItem) -> Unit,
    onYouTubeClick: (Song) -> Unit,
    onMoreClick: (Song) -> Unit,
    onRetryYouTube: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val localDensity = LocalDensity.current
    val imeBottomPadding = WindowInsets.ime.getBottom(localDensity).dp
    val systemBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 94.dp
    val resolvedBottomPadding = if (imeBottomPadding <= 8.dp) {
        MiniPlayerHeight + systemBarBottomPadding
    } else {
        imeBottomPadding
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + resolvedBottomPadding
        )
    ) {
        items(rows, key = { it.stableKey }, contentType = { it::class }) { row ->
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                when (row) {
                    is UnifiedSearchRow.LocalRow -> LocalResultRow(
                        item = row.item,
                        onClick = { onLocalClick(row.item) },
                        onMoreClick = onMoreClick
                    )

                    is UnifiedSearchRow.YouTubeRow -> {
                        EnhancedSongListItem(
                            song = row.song,
                            isPlaying = false,
                            onMoreOptionsClick = onMoreClick,
                            onClick = { onYouTubeClick(row.song) }
                        )
                        YouTubeSourceBadge(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, end = 4.dp)
                        )
                    }
                }
            }
        }

        if (youTubeError != null) {
            item(key = "unified_youtube_error") {
                UnifiedYouTubeStatusRow(
                    message = stringResource(
                        when (youTubeError) {
                            YouTubeSearchStateHolder.Error.OFFLINE -> R.string.search_youtube_offline
                            else -> R.string.search_youtube_error
                        }
                    ),
                    onRetry = onRetryYouTube
                )
            }
        } else if (isYouTubeLoading) {
            item(key = "unified_youtube_loading") { UnifiedYouTubeLoadingRow() }
        }
    }
}

@Composable
private fun LocalResultRow(
    item: SearchResultItem,
    onClick: () -> Unit,
    onMoreClick: (Song) -> Unit
) {
    when (item) {
        is SearchResultItem.SongItem -> EnhancedSongListItem(
            song = item.song,
            isPlaying = false,
            onMoreOptionsClick = onMoreClick,
            onClick = onClick
        )

        is SearchResultItem.AlbumItem -> LocalBrowseRow(
            icon = Icons.Rounded.Album,
            title = item.album.title,
            subtitle = item.album.artist,
            onClick = onClick
        )

        is SearchResultItem.ArtistItem -> LocalBrowseRow(
            icon = Icons.Rounded.Person,
            title = item.artist.name,
            subtitle = formatSongCount(item.artist.songCount),
            onClick = onClick
        )

        is SearchResultItem.PlaylistItem -> LocalBrowseRow(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            title = item.playlist.name,
            subtitle = formatSongCount(item.playlist.songIds.size),
            onClick = onClick
        )
    }
}

@Composable
private fun LocalBrowseRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun YouTubeSourceBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = stringResource(R.string.unified_search_youtube_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun UnifiedYouTubeLoadingRow() {
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
private fun UnifiedYouTubeStatusRow(message: String, onRetry: () -> Unit) {
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
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.search_youtube_retry))
        }
    }
}
