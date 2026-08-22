package com.lostf1sh.pixelplayeross.data.network.deezer

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for Deezer API.
 * Used for artist artwork and for resolving a track's genre.
 */
interface DeezerApiService {

    /**
     * Search for an artist by name.
     * @param query Artist name to search for
     * @param limit Maximum number of results to return
     * @return Search response containing list of matching artists
     */
    @GET("search/artist")
    suspend fun searchArtist(
        @Query("q") query: String,
        @Query("limit") limit: Int = 1
    ): DeezerSearchResponse

    /**
     * Search for a track. Deezer supports field operators in the query, so a caller can ask
     * either strictly (`artist:"x" track:"y"`) or loosely (plain words).
     *
     * @param query Search query
     * @param limit Maximum number of results to return
     */
    @GET("search/track")
    suspend fun searchTrack(
        @Query("q") query: String,
        @Query("limit") limit: Int = 5
    ): DeezerTrackSearchResponse

    /**
     * Fetch a full album, which is the only place Deezer exposes a genre.
     *
     * @param albumId Deezer album id, taken from a track search result
     */
    @GET("album/{id}")
    suspend fun getAlbum(@Path("id") albumId: Long): DeezerAlbum
}
