package com.dodoznq.helora.data.youtube

/**
 * One track from an InnerTube search response, before cleanup/mapping into
 * [com.dodoznq.helora.data.model.Song].
 *
 * [albumTitle] / [albumBrowseId] are only ever present for a Songs-shelf row; the Videos shelf
 * carries a view count in that run position instead of an album, so both stay null there.
 */
data class InnerTubeSongItem(
    val videoId: String,
    val title: String,
    val artistName: String,
    val artistChannelId: String?,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val albumTitle: String? = null,
    val albumBrowseId: String? = null
)

/** One album/single/EP from an InnerTube search response. */
data class InnerTubeAlbumItem(
    val browseId: String,
    val title: String,
    val artistName: String,
    val thumbnailUrl: String?
)

/** One artist channel from an InnerTube search response. */
data class InnerTubeArtistItem(
    val channelId: String,
    val name: String,
    val thumbnailUrl: String?
)

/**
 * A parsed InnerTube search response, bucketed by shelf.
 *
 * [songs] merges the "Songs" shelf (official catalogue uploads) and the "Videos" shelf
 * (community uploads, fan edits, remixes, slowed/sped versions) — YouTube Music keeps these
 * in separate shelves, but the app's search treats them as the same kind of result.
 *
 * Each shelf's continuation token is kept around unused for a follow-up paging change; nothing
 * here fetches a next page yet.
 */
data class InnerTubeSearchPage(
    val songs: List<InnerTubeSongItem> = emptyList(),
    val albums: List<InnerTubeAlbumItem> = emptyList(),
    val artists: List<InnerTubeArtistItem> = emptyList(),
    val songsContinuation: String? = null,
    val videosContinuation: String? = null,
    val albumsContinuation: String? = null
)

/**
 * A parsed `get_search_suggestions` response.
 *
 * [completions] are plain text query completions, meant to fill the search box on tap.
 * [songs] are entities YouTube Music offers as directly playable from the suggestion list.
 */
data class InnerTubeSuggestions(
    val completions: List<String> = emptyList(),
    val songs: List<InnerTubeSongItem> = emptyList()
)
