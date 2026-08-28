package com.dodoznq.helora.data.search

import com.dodoznq.helora.data.model.SearchResultItem
import com.dodoznq.helora.data.model.Song
import kotlin.math.abs
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** One row in the merged search results list, keyed for stable LazyColumn diffing. */
sealed interface UnifiedSearchRow {
    val stableKey: String

    data class LocalRow(val item: SearchResultItem) : UnifiedSearchRow {
        override val stableKey: String = "local:${item.stableIdPart()}"
    }

    data class YouTubeRow(val song: Song) : UnifiedSearchRow {
        override val stableKey: String = "yt:${song.ytVideoId ?: song.id}"
    }
}

private fun SearchResultItem.stableIdPart(): String = when (this) {
    is SearchResultItem.SongItem -> "song:${song.id}"
    is SearchResultItem.AlbumItem -> "album:${album.id}"
    is SearchResultItem.ArtistItem -> "artist:${artist.id}"
    is SearchResultItem.PlaylistItem -> "playlist:${playlist.id}"
}

private fun dedupeKey(title: String, artist: String): String =
    "${SearchTextNormalizer.normalize(title)}|${SearchTextNormalizer.normalize(artist)}"

/**
 * Merges local (Room+FTS) and YouTube search results into one display list, dropping
 * YouTube songs that duplicate a local song. Local results always win: the user's own
 * file is preferred over the network stream for the same track.
 */
object UnifiedSearchMerger {

    fun merge(
        local: List<SearchResultItem>,
        youtube: List<Song>,
        durationToleranceMs: Long = 3_000L
    ): ImmutableList<UnifiedSearchRow> {
        val localSongsByKey = HashMap<String, MutableList<Song>>()
        for (item in local) {
            if (item is SearchResultItem.SongItem) {
                val key = dedupeKey(item.song.title, item.song.artist)
                localSongsByKey.getOrPut(key) { mutableListOf() }.add(item.song)
            }
        }

        val localRows = local.map(UnifiedSearchRow::LocalRow)

        val youTubeRows = youtube.mapNotNull { song ->
            val key = dedupeKey(song.title, song.artist)
            val matchesLocal = localSongsByKey[key]?.any { local ->
                abs(local.duration - song.duration) <= durationToleranceMs
            } == true
            if (matchesLocal) null else UnifiedSearchRow.YouTubeRow(song)
        }

        return (localRows + youTubeRows).toImmutableList()
    }
}
