package com.lostf1sh.pixelplayeross.data.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Downloader] implementation backed by the app's shared OkHttp client.
 *
 * Two deliberate deviations from that client:
 *  - It pins `User-Agent: PixelPlayer/1.0 …` with `.header()`, which *replaces*. YouTube
 *    degrades or rejects unknown user agents, so a browser UA is appended here. Interceptors
 *    run in the order they were added and `newBuilder()` appends, so this one wins.
 *  - Its 8s read timeout is too tight for InnerTube; extraction routinely needs longer.
 *
 * The shared client itself is never mutated — this is a YouTube-local concern.
 */
@Singleton
class NewPipeOkHttpDownloader @Inject constructor(
    baseClient: OkHttpClient
) : Downloader() {

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build()
            )
        }
        .build()

    /** Blocking by contract — callers must confine this to an IO dispatcher. */
    override fun execute(request: Request): Response {
        val url = request.url()

        val requestBuilder = okhttp3.Request.Builder()
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .url(url)

        request.headers().forEach { (headerName, headerValues) ->
            // Replace rather than append: NewPipe relies on being able to override headers
            // the shared client would otherwise contribute.
            requestBuilder.removeHeader(headerName)
            headerValues.forEach { value -> requestBuilder.addHeader(headerName, value) }
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                // NewPipe callers special-case this; surfacing it as a generic IOException
                // would hide rate limiting behind a "network error".
                throw ReCaptchaException("reCaptcha challenge requested", url)
            }

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString()
            )
        }
    }

    private companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
