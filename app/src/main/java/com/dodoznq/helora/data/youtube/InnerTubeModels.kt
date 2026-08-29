package com.dodoznq.helora.data.youtube

/** One track from an InnerTube search response, before cleanup/mapping into [com.dodoznq.helora.data.model.Song]. */
data class InnerTubeSongItem(
    val videoId: String,
    val title: String,
    val artistName: String,
    val artistChannelId: String?,
    val durationMs: Long,
    val thumbnailUrl: String?
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
 */
data class InnerTubeSearchPage(
    val songs: List<InnerTubeSongItem> = emptyList(),
    val albums: List<InnerTubeAlbumItem> = emptyList(),
    val artists: List<InnerTubeArtistItem> = emptyList()
)
