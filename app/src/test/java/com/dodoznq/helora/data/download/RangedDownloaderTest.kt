package com.dodoznq.helora.data.download

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The download loop has to hold three behaviours together: assemble chunks in order, notice
 * when a server ignores the Range header, and stop without a declared total. Each is covered
 * against a real socket rather than a mocked client, since the bugs live in header handling.
 */
class RangedDownloaderTest {

    private lateinit var server: HttpServer
    private val requests = AtomicInteger(0)
    private val rangeHeaders = mutableListOf<String?>()

    // Deliberately not a whole number of chunks, so the final short chunk is exercised.
    private val payload = ByteArray(2 * 1024 * 1024 + 12_345) { (it % 251).toByte() }

    private val client = OkHttpClient.Builder().build()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun url() = "http://127.0.0.1:${server.address.port}/track.m4a"

    private fun serve(handler: (HttpExchange) -> Unit) {
        server.createContext("/track.m4a") { exchange ->
            requests.incrementAndGet()
            rangeHeaders += exchange.requestHeaders.getFirst("Range")
            handler(exchange)
            exchange.close()
        }
        server.start()
    }

    private fun respondPartial(exchange: HttpExchange, declareTotal: Boolean) {
        val range = exchange.requestHeaders.getFirst("Range")
        val spec = range.removePrefix("bytes=").split("-")
        val start = spec[0].toInt()
        val end = minOf(spec[1].toInt(), payload.size - 1)
        val slice = payload.copyOfRange(start, end + 1)
        val total = if (declareTotal) payload.size.toString() else "*"
        exchange.responseHeaders.add("Content-Type", "audio/mp4")
        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
        exchange.sendResponseHeaders(206, slice.size.toLong())
        exchange.responseBody.write(slice)
    }

    private fun download(): Pair<RangedDownloader.Result, ByteArray> {
        val sink = ByteArrayOutputStream()
        val result = runBlocking {
            RangedDownloader(client).download(
                url = url(),
                output = sink,
                progressStepBytes = 512L * 1024L
            ) { _, _ -> }
        }
        return result to sink.toByteArray()
    }

    @Test
    fun `assembles a file from ranged chunks in order`() {
        serve { respondPartial(it, declareTotal = true) }

        val (result, bytes) = download()

        assertThat(bytes.size).isEqualTo(payload.size)
        assertThat(bytes).isEqualTo(payload)
        assertThat(result.copied).isEqualTo(payload.size.toLong())
        assertThat(result.total).isEqualTo(payload.size.toLong())
        assertThat(result.contentType).isEqualTo("audio/mp4")
        // Three requests: two full megabytes and the short remainder.
        assertThat(requests.get()).isEqualTo(3)
        assertThat(rangeHeaders).containsExactly(
            "bytes=0-1048575",
            "bytes=1048576-2097151",
            "bytes=2097152-3145727"
        ).inOrder()
    }

    @Test
    fun `stops on a short chunk when the server never declares a total`() {
        serve { respondPartial(it, declareTotal = false) }

        val (result, bytes) = download()

        assertThat(bytes).isEqualTo(payload)
        assertThat(result.total).isNull()
        assertThat(result.copied).isEqualTo(payload.size.toLong())
    }

    @Test
    fun `falls back to a single stream when the server ignores the range header`() {
        serve { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/mp4")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload)
        }

        val (result, bytes) = download()

        assertThat(bytes).isEqualTo(payload)
        assertThat(result.total).isEqualTo(payload.size.toLong())
        // One request only. Asking for more ranges would have duplicated the whole body.
        assertThat(requests.get()).isEqualTo(1)
    }

    /**
     * The nastier version of the same case: the server ignores Range and streams the body
     * without a length. Nothing then bounds the loop except knowing that ranges were refused,
     * so without that flag the next request appends the whole file to itself.
     */
    @Test
    fun `does not request a second body when a rangeless reply has no length`() {
        serve { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/mp4")
            // Zero means chunked transfer encoding here, so no Content-Length is sent.
            exchange.sendResponseHeaders(200, 0L)
            exchange.responseBody.write(payload)
        }

        val (result, bytes) = download()

        assertThat(bytes.size).isEqualTo(payload.size)
        assertThat(bytes).isEqualTo(payload)
        assertThat(result.copied).isEqualTo(payload.size.toLong())
        assertThat(requests.get()).isEqualTo(1)
    }

    @Test
    fun `reports the final bytes so progress reaches the total`() {
        serve { respondPartial(it, declareTotal = true) }

        val reported = mutableListOf<Long>()
        val sink = ByteArrayOutputStream()
        val result = runBlocking {
            RangedDownloader(client).download(
                url = url(),
                output = sink,
                progressStepBytes = 512L * 1024L
            ) { copied, _ -> reported += copied }
        }

        assertThat(reported.last()).isEqualTo(result.total)
        assertThat(reported.last()).isEqualTo(payload.size.toLong())
    }

    @Test
    fun `refuses a response that is not audio`() {
        serve { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, 5L)
            exchange.responseBody.write("hello".toByteArray())
        }

        assertThrows(IOException::class.java) { download() }
    }

    @Test
    fun `surfaces the status code so the worker can decide about retrying`() {
        serve { exchange ->
            exchange.sendResponseHeaders(503, -1L)
        }

        val thrown = assertThrows(RangedDownloader.HttpStatusException::class.java) { download() }
        assertThat(thrown.code).isEqualTo(503)
    }
}
