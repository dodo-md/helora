package com.dodoznq.helora.data.image

import android.content.Context
import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Size
import com.google.common.truth.Truth.assertThat
import com.dodoznq.helora.MainCoroutineExtension
import com.dodoznq.helora.data.youtube.YouTubeArtworkUrls
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * The interceptor is what makes one stored thumbnail URL serve a search row and the full player
 * at the right size each, so the size it derives from the Coil request is the thing to pin down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class YouTubeArtworkInterceptorTest {

    private val songThumb =
        "https://yt3.googleusercontent.com/-h_EmlrGLsJwhih3lVHGDmK3CAmLdpqEIeerAuJ71qmJeZ_5zYf9ElWIEBNiB_6_CbXh4qHchBpOqhCu=w120-h120-l90-rj"

    private val context = mockk<Context>(relaxed = true)

    /** Returns the data the interceptor actually forwarded down the chain. */
    private suspend fun dataAfterIntercept(data: Any?, size: Size): Any? {
        val request = ImageRequest.Builder(context).data(data).build()
        val forwarded = slot<ImageRequest>()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request } returns request
        every { chain.size } returns size
        coEvery { chain.proceed(capture(forwarded)) } returns mockk<ImageResult>(relaxed = true)

        YouTubeArtworkInterceptor().intercept(chain)
        return forwarded.captured.data
    }

    @Test
    fun `asks for the size the request was made at`() = runTest {
        // The now-playing carousel at MEDIUM album art quality.
        assertThat(dataAfterIntercept(songThumb, Size(512, 512)))
            .isEqualTo(songThumb.replace("=w120-h120-", "=w512-h512-"))
    }

    @Test
    fun `a list row keeps fetching a thumbnail`() = runTest {
        // The point of doing this per request rather than at the source: a search row must not
        // start pulling the large image.
        assertThat(dataAfterIntercept(songThumb, Size(144, 144)))
            .isEqualTo(songThumb.replace("=w120-h120-", "=w144-h144-"))
    }

    @Test
    fun `takes the larger edge when the request is not square`() = runTest {
        assertThat(dataAfterIntercept(songThumb, Size(200, 640)))
            .isEqualTo(songThumb.replace("=w120-h120-", "=w640-h640-"))
    }

    @Test
    fun `an undefined size asks for the maximum`() = runTest {
        // ORIGINAL album art quality, or a request made before layout has measured anything.
        val max = YouTubeArtworkUrls.MAX_SIZE_PX
        assertThat(dataAfterIntercept(songThumb, Size(Dimension.Undefined, Dimension.Undefined)))
            .isEqualTo(songThumb.replace("=w120-h120-", "=w$max-h$max-"))
    }

    @Test
    fun `leaves other sources untouched`() = runTest {
        listOf(
            "navidrome_cover://song-123",
            "content://media/external/audio/albumart/42",
            "https://coverartarchive.org/release/abc/front-500.jpg"
        ).forEach { url ->
            assertThat(dataAfterIntercept(url, Size(512, 512))).isEqualTo(url)
        }
    }
}
