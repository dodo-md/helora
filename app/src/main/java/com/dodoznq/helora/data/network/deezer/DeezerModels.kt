package com.dodoznq.helora.data.network.deezer

import com.google.gson.annotations.SerializedName

/**
 * Response from Deezer artist search API.
 */
data class DeezerSearchResponse(
    @SerializedName("data") val data: List<DeezerArtist> = emptyList(),
    @SerializedName("total") val total: Int = 0
)

/**
 * Artist data from Deezer API.
 * Contains multiple image sizes for different use cases.
 */
data class DeezerArtist(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picture") val picture: String? = null,
    @SerializedName("picture_small") val pictureSmall: String? = null,
    @SerializedName("picture_medium") val pictureMedium: String? = null,
    @SerializedName("picture_big") val pictureBig: String? = null,
    @SerializedName("picture_xl") val pictureXl: String? = null,
    @SerializedName("nb_album") val albumCount: Int = 0,
    @SerializedName("nb_fan") val fanCount: Int = 0
)

/**
 * Response from Deezer track search.
 */
data class DeezerTrackSearchResponse(
    @SerializedName("data") val data: List<DeezerTrack> = emptyList()
)

/**
 * A track from Deezer search. Deezer does not put a genre on the track itself, only on the
 * album, so [album] is what a genre lookup follows.
 */
data class DeezerTrack(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("artist") val artist: DeezerArtist? = null,
    @SerializedName("album") val album: DeezerTrackAlbum? = null
)

/**
 * The trimmed album Deezer nests inside a track result. Only the id is useful here, since the
 * genre lives on the full album resource.
 */
data class DeezerTrackAlbum(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("title") val title: String = ""
)

/**
 * Full album resource. [genreId] names the album's primary genre and [genres] lists every genre
 * it was tagged with, ordered loosely by relevance.
 */
data class DeezerAlbum(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("genre_id") val genreId: Long = -1,
    @SerializedName("genres") val genres: DeezerGenreList? = null
)

data class DeezerGenreList(
    @SerializedName("data") val data: List<DeezerGenre> = emptyList()
)

data class DeezerGenre(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("name") val name: String = ""
)
