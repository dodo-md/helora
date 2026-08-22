package com.dodoznq.helora.data.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SharedArtworkContentProviderTest {

    @Test
    fun buildSongUri_usesDedicatedArtworkAuthority() {
        val uri = SharedArtworkContentProvider.buildSongUriString(
            packageName = "com.dodoznq.helora",
            songId = 42L
        )

        assertThat(uri).isEqualTo("content://com.dodoznq.helora.artwork/song/42")
    }

    @Test
    fun buildSongUri_preservesCacheBustToken() {
        val uri = SharedArtworkContentProvider.buildSongUriString(
            packageName = "com.dodoznq.helora",
            songId = 42L,
            cacheBustToken = "1234"
        )

        assertThat(uri)
            .isEqualTo("content://com.dodoznq.helora.artwork/song/42?t=1234")
    }

    @Test
    fun parseSongId_rejectsOtherAuthorities() {
        val songId = SharedArtworkContentProvider.parseSongId(
            uriString = "content://example.com.artwork/song/42",
            packageName = "com.dodoznq.helora"
        )

        assertThat(songId).isNull()
    }

    @Test
    fun parseSongId_readsSharedArtworkSongUri() {
        val songId = SharedArtworkContentProvider.parseSongId(
            uriString = "content://com.dodoznq.helora.artwork/song/42",
            packageName = "com.dodoznq.helora"
        )

        assertThat(songId).isEqualTo(42L)
    }

    @Test
    fun cloudArtworkUri_roundTripsNavidromeArtwork() {
        val rawArtworkUri = "navidrome_cover://album-42"
        val sharedUri = SharedArtworkContentProvider.buildCloudUriString(
            packageName = "com.dodoznq.helora",
            rawArtworkUri = rawArtworkUri,
        )

        assertThat(sharedUri).isNotNull()
        assertThat(
            SharedArtworkContentProvider.parseCloudArtworkUri(
                uriString = sharedUri!!,
                packageName = "com.dodoznq.helora",
            )
        ).isEqualTo(rawArtworkUri)
    }

    @Test
    fun cloudArtworkUri_roundTripsJellyfinArtwork() {
        val rawArtworkUri = "jellyfin_cover://item-84"
        val sharedUri = SharedArtworkContentProvider.buildCloudUriString(
            packageName = "com.dodoznq.helora",
            rawArtworkUri = rawArtworkUri,
        )

        assertThat(sharedUri).isNotNull()
        assertThat(
            SharedArtworkContentProvider.parseCloudArtworkUri(
                uriString = sharedUri!!,
                packageName = "com.dodoznq.helora",
            )
        ).isEqualTo(rawArtworkUri)
    }

    @Test
    fun cloudArtworkUri_rejectsUnsupportedRemoteArtwork() {
        val sharedUri = SharedArtworkContentProvider.buildCloudUriString(
            packageName = "com.dodoznq.helora",
            rawArtworkUri = "https://example.com/cover.jpg",
        )

        assertThat(sharedUri).isNull()
    }
}
