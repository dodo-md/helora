package com.dodoznq.helora.data.image

import android.net.Uri
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Size
import com.dodoznq.helora.data.youtube.YouTubeArtworkUrls

/**
 * Asks YouTube for artwork at the size the caller actually needs.
 *
 * A YouTube song result carries a 120x120 thumbnail and nothing larger, so the now-playing
 * carousel, the notification and the lock screen were all upscaling a postage stamp. The size
 * lives in the URL, so it can simply be rewritten — see [YouTubeArtworkUrls].
 *
 * Doing it here rather than at the point the URL is stored means every surface gets the right
 * size on its own: a search row still fetches ~11 KB while the full player fetches the large
 * one, without a single call site having to know about any of this. Coil is already told the
 * target size of every request, which is the number this needs.
 */
class YouTubeArtworkInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = when (val data = request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> null
        }

        if (!YouTubeArtworkUrls.isResizable(url)) {
            return chain.proceed(request)
        }

        // An undefined dimension means the target is "as large as it comes" (ORIGINAL quality,
        // or a request made before layout). The source clamps itself, so asking high is safe.
        val requestedPx = chain.size.largestPixelDimension() ?: YouTubeArtworkUrls.MAX_SIZE_PX
        val resized = YouTubeArtworkUrls.withSize(url, requestedPx)

        if (resized == null || resized == url) {
            return chain.proceed(request)
        }

        // Only the data is swapped. Any explicit memory cache key set by the caller already
        // encodes the original URL together with its target size, so the sizes stay in separate
        // cache entries instead of one evicting the other.
        return chain.proceed(request.newBuilder().data(resized).build())
    }

    private fun Size.largestPixelDimension(): Int? {
        val widthPx = (width as? Dimension.Pixels)?.px
        val heightPx = (height as? Dimension.Pixels)?.px
        return listOfNotNull(widthPx, heightPx).maxOrNull()?.takeIf { it > 0 }
    }
}
