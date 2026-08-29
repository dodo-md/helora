package com.dodoznq.helora.data.youtube

import com.dodoznq.helora.data.model.Album
import com.dodoznq.helora.data.model.Artist
import com.dodoznq.helora.data.model.Song

/** A YouTube Music album (or single/EP) plus its tracks, as returned by a detail lookup. */
data class YouTubeAlbumDetail(
    val album: Album,
    val songs: List<Song>
)

/** A YouTube Music artist plus their top tracks and albums. */
data class YouTubeArtistDetail(
    val artist: Artist,
    val songs: List<Song>,
    val albums: List<Album>
)

/** Aggregated result of a single YouTube Music search. */
data class YouTubeSearchResult(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList()
)

/**
 * Autocomplete for the search box: plain text completions to fill the query, plus entities
 * YouTube Music offers as directly playable from the suggestion list.
 */
data class YouTubeSearchSuggestions(
    val completions: List<String> = emptyList(),
    val songs: List<Song> = emptyList()
)

/**
 * One slice of an open radio station, plus the cursor for the next slice.
 *
 * [stationUrl] is carried along because NewPipe needs the originating URL to fetch further
 * pages of the same mix.
 */
data class RadioPage(
    val songs: List<Song>,
    val nextPage: org.schabi.newpipe.extractor.Page?,
    val stationUrl: String
)

/** A concrete audio stream chosen for saving to disk. */
data class DownloadableStream(
    val url: String,
    val mimeType: String,
    val extension: String,
    val averageBitrate: Int
)
