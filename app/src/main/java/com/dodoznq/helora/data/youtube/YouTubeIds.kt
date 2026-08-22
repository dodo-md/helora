package com.dodoznq.helora.data.youtube

import kotlin.math.absoluteValue

/**
 * Deterministic numeric identifiers for YouTube Music entities.
 *
 * YouTube tracks are ephemeral by default: they only reach Room when the user favorites
 * them or adds them to a playlist. To make that promotion seamless, the id a `Song` carries
 * while ephemeral must already equal the `SongEntity.id` it will get once persisted, so
 * favorites and playlist rows written beforehand keep pointing at the right row.
 *
 * Ids are negative to stay clear of MediaStore ids, and use a dedicated offset block
 * (the other cloud sources occupy 3T..14T).
 *
 * Unlike those sources, this hashes with 64-bit FNV-1a rather than [String.hashCode]:
 * a 32-bit hash has roughly even odds of colliding around 77k distinct values, and a radio
 * session generates tracks indefinitely.
 */
object YouTubeIds {
    private const val SONG_ID_OFFSET = 15_000_000_000_000L
    private const val ALBUM_ID_OFFSET = 17_000_000_000_000L
    private const val ARTIST_ID_OFFSET = 19_000_000_000_000L

    /** Keeps each hash inside its own ~2^40 block so the offsets never overlap. */
    private const val HASH_SPACE = 1_000_000_000_000L

    private const val FNV_OFFSET_BASIS = -3750763034362895579L // 14695981039346656037 unsigned
    private const val FNV_PRIME = 1099511628211L

    private fun fnv1a64(value: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (char in value) {
            hash = hash xor (char.code.toLong() and 0xFFL)
            hash *= FNV_PRIME
        }
        return hash
    }

    private fun scopedId(offset: Long, value: String): Long =
        -(offset + (fnv1a64(value) % HASH_SPACE).absoluteValue)

    fun songId(videoId: String): Long = scopedId(SONG_ID_OFFSET, videoId)

    fun albumId(browseId: String): Long = scopedId(ALBUM_ID_OFFSET, browseId)

    fun artistId(channelId: String): Long = scopedId(ARTIST_ID_OFFSET, channelId)
}
