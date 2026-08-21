package com.lostf1sh.pixelplayeross.data.model

import androidx.compose.runtime.Immutable

@Immutable
enum class SearchFilterType {
    ALL,
    SONGS,
    ALBUMS,
    ARTISTS,
    PLAYLISTS,

    /**
     * YouTube Music. Results come from a separate, network-backed holder rather than the local
     * index, so the local search path returns nothing for this filter.
     */
    YOUTUBE
}
