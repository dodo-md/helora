package com.lostf1sh.pixelplayeross.presentation.navigation

/**
 * Encodes a remote source's own identifier into the album/artist detail routes.
 *
 * Those destinations were built for numeric library ids but their nav arguments have always
 * been declared as strings, so a prefixed opaque id rides the same route and the detail
 * ViewModel picks the loader based on the prefix. That keeps one detail screen instead of a
 * parallel remote-only copy.
 */
object RemoteDetailId {
    private const val YOUTUBE_PREFIX = "ytm:"

    fun youTubeAlbum(browseId: String): String = YOUTUBE_PREFIX + browseId

    fun youTubeArtist(channelId: String): String = YOUTUBE_PREFIX + channelId

    /** Returns the YouTube id carried by [raw], or null when it is an ordinary library id. */
    fun youTubeIdOrNull(raw: String?): String? =
        raw?.takeIf { it.startsWith(YOUTUBE_PREFIX) }?.removePrefix(YOUTUBE_PREFIX)
}
