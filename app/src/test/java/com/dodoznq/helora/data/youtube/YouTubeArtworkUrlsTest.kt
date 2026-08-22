package com.dodoznq.helora.data.youtube

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The URLs here are verbatim from live YouTube Music responses, because the whole trick depends
 * on the exact shape of that size parameter.
 */
class YouTubeArtworkUrlsTest {

    private val songThumb =
        "https://yt3.googleusercontent.com/-h_EmlrGLsJwhih3lVHGDmK3CAmLdpqEIeerAuJ71qmJeZ_5zYf9ElWIEBNiB_6_CbXh4qHchBpOqhCu=w120-h120-l90-rj"

    // Artist avatars carry an extra `-p` flag between the size and the rest.
    private val artistThumb =
        "https://lh3.googleusercontent.com/G-Vsaq4pw6Bl5Ny4p_wV9obzu_eyZxynQycvCvQru4Wglzfxg4NO9owDKnSlKys_-WRzPEc0O6ydGA=w120-h120-p-l90-rj"

    @Test
    fun `rewrites the size and keeps every other flag`() {
        assertThat(YouTubeArtworkUrls.withSize(songThumb, 544)).isEqualTo(
            songThumb.replace("=w120-h120-", "=w544-h544-")
        )
        // The -p must survive: dropping it changes how the avatar is cropped.
        assertThat(YouTubeArtworkUrls.withSize(artistThumb, 544)).isEqualTo(
            artistThumb.replace("=w120-h120-", "=w544-h544-")
        )
        assertThat(YouTubeArtworkUrls.withSize(artistThumb, 544)).contains("-p-l90-rj")
    }

    @Test
    fun `clamps to what the source will actually serve`() {
        // Asking beyond the ceiling returns the ceiling image anyway, so the URL says so too.
        assertThat(YouTubeArtworkUrls.withSize(songThumb, 5_000))
            .contains("=w${YouTubeArtworkUrls.MAX_SIZE_PX}-h${YouTubeArtworkUrls.MAX_SIZE_PX}-")
        // Below the floor there is nothing to gain from a rewrite.
        assertThat(YouTubeArtworkUrls.withSize(songThumb, 16)).isEqualTo(songThumb)
    }

    @Test
    fun `leaves everything that is not youtube artwork alone`() {
        // The Coil interceptor sees every image the app loads, so this list is the guard rail.
        val untouched = listOf(
            "navidrome_cover://song-123",
            "jellyfin_cover://item-456",
            "content://media/external/audio/albumart/42",
            "file:///storage/emulated/0/Music/cover.jpg",
            "https://coverartarchive.org/release/abc/front-500.jpg",
            // Right shape, wrong host: must not be rewritten.
            "https://evil.example.com/a=w120-h120-l90-rj"
        )
        untouched.forEach { url ->
            assertThat(YouTubeArtworkUrls.withSize(url, 544)).isEqualTo(url)
            assertThat(YouTubeArtworkUrls.isResizable(url)).isFalse()
        }
    }

    @Test
    fun `passes through youtube urls that carry no size to rewrite`() {
        val noSizeParam = songThumb.substringBefore('=')
        assertThat(YouTubeArtworkUrls.isResizable(noSizeParam)).isFalse()
        assertThat(YouTubeArtworkUrls.withSize(noSizeParam, 544)).isEqualTo(noSizeParam)
    }

    @Test
    fun `handles null and blank`() {
        assertThat(YouTubeArtworkUrls.withSize(null, 544)).isNull()
        assertThat(YouTubeArtworkUrls.isResizable(null)).isFalse()
        assertThat(YouTubeArtworkUrls.isResizable("")).isFalse()
    }

    @Test
    fun `resizing is idempotent`() {
        // The now-playing URL can be resized again by the notification loader at a different
        // size; repeating the rewrite must not stack size parameters.
        val once = YouTubeArtworkUrls.withSize(songThumb, 544)
        val twice = YouTubeArtworkUrls.withSize(once, 1080)
        assertThat(twice).isEqualTo(YouTubeArtworkUrls.withSize(songThumb, 1080))
        assertThat(twice!!.count { it == '=' }).isEqualTo(1)
    }
}
