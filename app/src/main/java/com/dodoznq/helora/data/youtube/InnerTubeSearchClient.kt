package com.dodoznq.helora.data.youtube

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to YouTube Music's `search` InnerTube endpoint directly — the same request
 * music.youtube.com's own web client makes — instead of going through NewPipeExtractor.
 *
 * Anonymous: there is no login, and [API_KEY] is not a secret. It is the public WEB_REMIX
 * client key baked into music.youtube.com's page source; every browser that loads the site
 * sends it.
 */
@Singleton
class InnerTubeSearchClient @Inject constructor(
    baseClient: OkHttpClient
) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Blocking by contract — callers must confine this to an IO dispatcher. */
    fun search(query: String, country: String, language: String): JsonObject? {
        val request = Request.Builder()
            .url("$SEARCH_URL?key=$API_KEY&prettyPrint=false")
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/search")
            .header("Cookie", "SOCS=CAI") // skips the EU consent interstitial
            .post(requestBody(query, country, language))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string()
            if (!response.isSuccessful || text.isNullOrBlank()) return null
            return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        }
    }

    private fun requestBody(query: String, country: String, language: String): RequestBody {
        val clientContext = JsonObject().apply {
            addProperty("clientName", "WEB_REMIX")
            addProperty("clientVersion", CLIENT_VERSION)
            addProperty("hl", language)
            addProperty("gl", country)
        }
        val context = JsonObject().apply { add("client", clientContext) }
        val body = JsonObject().apply {
            add("context", context)
            addProperty("query", query)
        }
        return body.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private companion object {
        const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
        const val ORIGIN = "https://music.youtube.com"

        // Public WEB_REMIX InnerTube client key, shared by every YouTube Music web client.
        const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        const val CLIENT_VERSION = "1.20241201.01.00"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
    }
}
