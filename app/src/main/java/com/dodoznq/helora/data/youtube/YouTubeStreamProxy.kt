package com.dodoznq.helora.data.youtube

import android.net.Uri
import com.dodoznq.helora.data.stream.CloudStreamProxy
import com.dodoznq.helora.data.stream.CloudStreamSecurity
import com.dodoznq.helora.data.stream.StreamUrlExpiry
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local HTTP proxy for YouTube Music audio.
 *
 * Resolves `ytmusic://{videoId}` by extracting a progressive audio stream URL and piping the
 * bytes to ExoPlayer, the same shape as the other cloud sources.
 *
 * The upstream host is always a `*.googlevideo.com` edge (e.g. `rr3---sn-abc.googlevideo.com`),
 * which the suffix match in [CloudStreamSecurity] covers.
 */
@Singleton
class YouTubeStreamProxy @Inject constructor(
    private val repository: YouTubeMusicRepository,
    baseClient: OkHttpClient
) : CloudStreamProxy<String>(
    // Media reads are long-lived; the shared client's 8s read timeout would cut them off.
    baseClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", STREAM_USER_AGENT)
                    .build()
            )
        }
        .build()
) {

    override val allowedHostSuffixes: Set<String> = setOf("googlevideo.com")

    /**
     * Fallback used when a stream URL doesn't carry a readable `expire` timestamp, and the
     * hard ceiling in all cases: googlevideo URLs are IP-bound, so even a URL whose signed
     * expiry is still far off can die on a network change well before then.
     */
    override val cacheExpirationMs = 30L * 60 * 1000

    override fun expirationMsFor(url: String): Long =
        StreamUrlExpiry.remainingTtlMs(url, fallbackMs = cacheExpirationMs)

    override val proxyTag = "YouTubeStreamProxy"
    override val routePath = "/ytmusic/{videoId}"
    override val routeParamName = "videoId"
    override val uriScheme = YouTubeMusicRepository.URI_SCHEME
    override val routePrefix = "/ytmusic"

    override fun parseRouteParam(value: String): String? = value.takeIf { it.isNotBlank() }

    override fun validateId(id: String): Boolean = CloudStreamSecurity.validateYouTubeVideoId(id)

    override fun formatIdForUrl(id: String): String = id

    override suspend fun resolveStreamUrl(id: String): String? {
        return try {
            repository.getAudioStreamUrl(id)
        } catch (e: Exception) {
            Timber.w(e, "YouTubeStreamProxy: failed to resolve stream URL for %s", id)
            null
        }
    }

    // ytmusic://videoId, ytmusic:///videoId, or ytmusic:videoId
    override fun extractIdFromUri(uri: Uri): String? {
        val host = uri.host?.takeIf { it.isNotBlank() }
        val path = uri.path?.removePrefix("/")?.takeIf { it.isNotBlank() }
        val ssp = uri.schemeSpecificPart?.removePrefix("//")?.removePrefix("/")?.takeIf { it.isNotBlank() }
        return host ?: path ?: ssp
    }

    fun resolveYouTubeUri(uriString: String): String? = resolveUri(uriString)

    /**
     * Pre-fetches the real stream URL so the proxy can answer ExoPlayer's first byte-range
     * request without blocking on extraction.
     */
    suspend fun warmUpStreamUrl(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != uriScheme) return
        val videoId = extractIdFromUri(uri) ?: return
        if (!CloudStreamSecurity.validateYouTubeVideoId(videoId)) return
        try {
            val streamUrl = getOrFetchStreamUrl(videoId)
            if (streamUrl == null) {
                Timber.tag(proxyTag).w("[YT_DEBUG] stream warm-up returned no URL; id=%s", videoId)
            } else {
                Timber.tag(proxyTag).d("[YT_DEBUG] stream warm-up complete; id=%s", videoId)
            }
        } catch (e: Exception) {
            Timber.w(e, "warmUpStreamUrl failed for %s", videoId)
        }
    }

    private companion object {
        const val STREAM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
    }
}
