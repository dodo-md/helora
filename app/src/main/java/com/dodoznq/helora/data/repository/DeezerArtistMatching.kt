package com.dodoznq.helora.data.repository

import com.dodoznq.helora.data.network.deezer.DeezerArtist
import com.dodoznq.helora.utils.ArtistNameMatching

/** Picking the right artist out of a Deezer search. */
internal object DeezerArtistMatching {

    fun normalize(value: String): String = ArtistNameMatching.normalize(value)

    fun artistMatches(wanted: String, candidate: String): Boolean =
        ArtistNameMatching.matches(wanted, candidate)

    /**
     * Picks the artist a search was actually asking for.
     *
     * Deezer carries duplicate entries for the same act, most of them near-empty, and the one
     * a search returns first is regularly the wrong one: searching "Sezen Aksu" leads with an
     * entry that has 15 fans and no related artists, while the real one has over a million.
     * Popularity settles that.
     *
     * The name filter has to come first, though, and cannot be dropped for being redundant:
     * "Tarkan" also returns Reynmen, who is more popular than Tarkan is.
     */
    fun bestMatch(query: String, candidates: List<DeezerArtist>): DeezerArtist? {
        val wanted = ArtistNameMatching.normalize(query)
        if (wanted.isEmpty()) return null
        return candidates
            .filter { ArtistNameMatching.normalize(it.name) == wanted }
            .maxByOrNull { it.fanCount }
    }
}
