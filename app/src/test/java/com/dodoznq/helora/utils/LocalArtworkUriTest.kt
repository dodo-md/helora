package com.dodoznq.helora.utils

import com.google.common.truth.Truth.assertThat
import com.dodoznq.helora.data.database.AlbumEntity
import com.dodoznq.helora.data.database.toAlbum
import org.junit.Test

class LocalArtworkUriTest {

    @Test
    fun resolveSongArtworkUri_convertsLegacyLocalCacheUriToStableUri() {
        val resolved = LocalArtworkUri.resolveSongArtworkUri(
            storedUri = "content://com.dodoznq.helora.provider/cache/song_art_42.jpg",
            songId = 42L,
            contentUriString = "content://media/external/audio/media/42"
        )

        assertThat(resolved).isEqualTo(LocalArtworkUri.buildSongUri(42L))
    }

    @Test
    fun resolveSongArtworkUri_convertsSharedArtworkUriToStableUri() {
        val resolved = LocalArtworkUri.resolveSongArtworkUri(
            storedUri = "content://com.dodoznq.helora.artwork/song/42?t=1234",
            songId = 42L,
            contentUriString = "content://media/external/audio/media/42"
        )

        assertThat(resolved).isEqualTo(LocalArtworkUri.buildSongUri(42L))
    }

    @Test
    fun resolveSongArtworkUri_keepsRemoteArtworkUriUntouched() {
        val resolved = LocalArtworkUri.resolveSongArtworkUri(
            storedUri = "https://example.com/cover.jpg",
            songId = 42L,
            contentUriString = "content://media/external/audio/media/42"
        )

        assertThat(resolved).isEqualTo("https://example.com/cover.jpg")
    }

    @Test
    fun resolveSongArtworkUri_keepsCloudSourceArtworkUntouched() {
        val resolved = LocalArtworkUri.resolveSongArtworkUri(
            storedUri = "navidrome_cover://cover-123",
            songId = 42L,
            contentUriString = "navidrome://song-123"
        )

        assertThat(resolved).isEqualTo("navidrome_cover://cover-123")
    }

    /**
     * Every rename of the app has left a scheme behind in song rows and in backup files. These
     * two must keep resolving forever, so a future rename cannot quietly blank the artwork of
     * anything saved before it.
     */
    @Test
    fun isLocalArtworkUri_stillRecognisesSchemesWrittenByOlderVersions() {
        assertThat(LocalArtworkUri.isLocalArtworkUri("pixelplayer_local_art://song/42")).isTrue()
        assertThat(LocalArtworkUri.isLocalArtworkUri("pixelplay_local_art://song/42")).isTrue()
    }

    @Test
    fun parseSongId_readsSongUrisWrittenByOlderVersions() {
        assertThat(LocalArtworkUri.parseSongId("pixelplayer_local_art://song/42")).isEqualTo(42L)
        assertThat(LocalArtworkUri.parseSongId("pixelplay_local_art://song/42?t=99")).isEqualTo(42L)
    }

    @Test
    fun isLocalArtworkUri_rejectsAnUnrelatedScheme() {
        assertThat(LocalArtworkUri.isLocalArtworkUri("navidrome_cover://song/42")).isFalse()
    }

    @Test
    fun parseSongId_readsStableSongUri() {
        val songId = LocalArtworkUri.parseSongId(LocalArtworkUri.buildSongUri(99L))

        assertThat(songId).isEqualTo(99L)
    }

    @Test
    fun parseSongIdFromVolatileArtworkUri_readsLegacyCacheFileName() {
        val songId = LocalArtworkUri.parseSongIdFromVolatileArtworkUri(
            "content://com.dodoznq.helora.provider/cache/song_art_77_v2.jpg"
        )

        assertThat(songId).isEqualTo(77L)
    }

    @Test
    fun extractCacheBustToken_readsTimestampQuery() {
        val cacheBustToken = LocalArtworkUri.extractCacheBustToken(
            "helora_local_art://song/99?t=456"
        )

        assertThat(cacheBustToken).isEqualTo("456")
    }

    @Test
    fun toAlbum_convertsLegacyCacheUriToStableUri() {
        val entity = AlbumEntity(
            id = 1L,
            title = "Test Album",
            artistName = "Test Artist",
            artistId = 10L,
            albumArtUriString = "content://com.dodoznq.helora.provider/cache/song_art_42.jpg",
            songCount = 12,
            dateAdded = 1000L,
            year = 2024
        )

        val album = entity.toAlbum()

        assertThat(album.albumArtUriString).isEqualTo(LocalArtworkUri.buildSongUri(42L))
    }

    @Test
    fun toAlbum_convertsSharedArtworkUriToStableUri() {
        val entity = AlbumEntity(
            id = 2L,
            title = "Shared Album",
            artistName = "Artist",
            artistId = 20L,
            albumArtUriString = "content://com.dodoznq.helora.artwork/song/99?t=1234",
            songCount = 8,
            dateAdded = 2000L,
            year = 2023
        )

        val album = entity.toAlbum()

        assertThat(album.albumArtUriString).isEqualTo(LocalArtworkUri.buildSongUri(99L))
    }

    @Test
    fun toAlbum_keepsStableLocalArtworkUriUntouched() {
        val entity = AlbumEntity(
            id = 3L,
            title = "Stable",
            artistName = "Artist",
            artistId = 30L,
            albumArtUriString = LocalArtworkUri.buildSongUri(55L),
            songCount = 5,
            dateAdded = 3000L,
            year = 2022
        )

        val album = entity.toAlbum()

        assertThat(album.albumArtUriString).isEqualTo(LocalArtworkUri.buildSongUri(55L))
    }

    @Test
    fun toAlbum_keepsRemoteArtworkUriUntouched() {
        val entity = AlbumEntity(
            id = 4L,
            title = "Remote",
            artistName = "Artist",
            artistId = 40L,
            albumArtUriString = "https://example.com/cover.jpg",
            songCount = 3,
            dateAdded = 4000L,
            year = 2021
        )

        val album = entity.toAlbum()

        assertThat(album.albumArtUriString).isEqualTo("https://example.com/cover.jpg")
    }

    @Test
    fun toAlbum_returnsNullForNullArtworkUri() {
        val entity = AlbumEntity(
            id = 5L,
            title = "No Art",
            artistName = "Artist",
            artistId = 50L,
            albumArtUriString = null,
            songCount = 1,
            dateAdded = 5000L,
            year = 2020
        )

        val album = entity.toAlbum()

        assertThat(album.albumArtUriString).isNull()
    }

    @Test
    fun toAlbum_returnsNullForBlankArtworkUri() {
        val entity = AlbumEntity(
            id = 6L,
            title = "Blank Art",
            artistName = "Artist",
            artistId = 60L,
            albumArtUriString = "",
            songCount = 2,
            dateAdded = 6000L,
            year = 2019
        )

        val album = entity.toAlbum()

        assertThat(album.albumArtUriString).isNull()
    }
}
