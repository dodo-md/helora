package com.dodoznq.helora.data.download

import com.dodoznq.helora.data.stream.CloudStreamSecurity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream

/**
 * Fetches a remote audio file one ranged chunk per request.
 *
 * Googlevideo throttles a plain unranged GET down to roughly the track's own bitrate. Measured
 * on the same file, same connection, in both orders: a single GET moved 3.99 MB in 126 seconds
 * (30 KB/s), while one megabyte ranged chunks moved the same bytes in under two seconds. Self
 * hosted servers are not throttled, but they do honour ranges, so they take the same path.
 *
 * A server that answers 200 to a ranged request has ignored it and sent the whole file instead
 * of the first chunk. That reply is read to the end, which is what this replaced.
 */
class RangedDownloader(private val client: OkHttpClient) {

    data class Result(
        val copied: Long,
        val total: Long?,
        val contentType: String?
    )

    class HttpStatusException(val code: Int) : IOException("Server returned HTTP $code")

    suspend fun download(
        url: String,
        headers: Map<String, String> = emptyMap(),
        output: OutputStream,
        progressStepBytes: Long,
        onProgress: suspend (copied: Long, total: Long?) -> Unit
    ): Result {
        var copied = 0L
        var lastPublished = 0L
        var total: Long? = null
        var contentType: String? = null
        var serverHonoursRanges = true

        while (true) {
            currentCoroutineContext().ensureActive()
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            if (serverHonoursRanges) {
                builder.header("Range", "bytes=$copied-${copied + CHUNK_BYTES - 1}")
            }

            var chunk = 0L
            client.newCall(builder.get().build()).execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)

                if (contentType == null) {
                    contentType = response.header("Content-Type")
                    if (!CloudStreamSecurity.isSupportedAudioContentType(contentType)) {
                        throw IOException("Server returned a non-audio response")
                    }
                }

                if (response.code == HTTP_PARTIAL_CONTENT) {
                    if (total == null) {
                        val declared = CloudStreamSecurity
                            .totalLengthFromContentRange(response.header("Content-Range"))
                        if (declared != null &&
                            declared > CloudStreamSecurity.MAX_STREAM_CONTENT_LENGTH_BYTES
                        ) {
                            throw IOException("Audio file is too large")
                        }
                        total = declared
                    }
                } else {
                    // Ranges were asked for and ignored, so this body is the whole file. Bytes
                    // already on disk would be duplicated by a second full body, which is why
                    // this is only tolerated on the very first request.
                    if (copied > 0L) throw IOException("Server stopped honouring range requests")
                    serverHonoursRanges = false
                    if (!CloudStreamSecurity
                            .isAcceptableContentLength(response.header("Content-Length"))
                    ) {
                        throw IOException("Audio file is too large")
                    }
                    total = response.body.contentLength().takeIf { it >= 0L }
                }

                val buffer = ByteArray(COPY_BUFFER_BYTES)
                response.body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        chunk += count
                        copied += count
                        if (copied > CloudStreamSecurity.MAX_STREAM_CONTENT_LENGTH_BYTES) {
                            throw IOException("Audio file is too large")
                        }
                        if (copied - lastPublished >= progressStepBytes) {
                            onProgress(copied, total)
                            lastPublished = copied
                        }
                    }
                }
            }

            if (!serverHonoursRanges) break
            // A chunk shorter than requested is the end of the file, and it is the only signal
            // available when the server never declared a total.
            if (chunk < CHUNK_BYTES) break
            val known = total
            if (known != null && copied >= known) break
            if (chunk == 0L) throw IOException("Server returned an empty range")
        }

        // Progress is only pushed every step, so the tail of the file never triggered one and
        // the bar stopped short of full before the row flipped to complete.
        if (copied > lastPublished) onProgress(copied, total)

        return Result(copied = copied, total = total, contentType = contentType)
    }

    companion object {
        /**
         * One megabyte measured fastest against googlevideo. Smaller chunks pay the round trip
         * too often, larger ones start attracting the same throttle a single request gets.
         */
        const val CHUNK_BYTES = 1L * 1024L * 1024L
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val HTTP_PARTIAL_CONTENT = 206
    }
}
