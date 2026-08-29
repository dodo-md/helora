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
 *
 * The shared client's own interceptor pins `User-Agent: Helora/1.0 …` with `.header()`, which
 * *replaces*. YouTube degrades or rejects unknown user agents, so a browser UA is appended here
 * the same way [NewPipeOkHttpDownloader] does it: interceptors run in the order they were added
 * and `newBuilder()` appends, so this one wins over the shared client's.
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
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build()
            )
        }
        .build()

    /**
     * Runs one InnerTube search, optionally scoped to a category shelf via [params].
     *
     * An unfiltered search (params == null) groups results into a `musicCardShelfRenderer`
     * top result plus bare `itemSectionRenderer` blocks with no shelf title at all — there is
     * no "Songs"/"Albums"/"Artists" grouping to key off. A filtered search is the only shape
     * that returns exactly one `musicShelfRenderer`, which is why every caller here passes a
     * [SearchFilter].
     *
     * Blocking by contract — callers must confine this to an IO dispatcher.
     */
    fun search(query: String, country: String, language: String, params: String? = null): JsonObject? {
        val request = Request.Builder()
            .url("$SEARCH_URL?key=$API_KEY&prettyPrint=false")
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/search")
            .header("Cookie", "SOCS=CAI") // skips the EU consent interstitial
            .post(requestBody(query, country, language, params))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string()
            if (!response.isSuccessful || text.isNullOrBlank()) return null
            return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        }
    }

    /**
     * Fetches the next page of a shelf, given the continuation token from its previous page.
     *
     * A continuation request carries no `query` and no `params` — the token alone identifies
     * which search and which shelf to continue, which is also why the response shape differs
     * (`continuationContents.musicShelfContinuation` instead of a fresh `musicShelfRenderer`).
     *
     * Blocking by contract — callers must confine this to an IO dispatcher.
     */
    fun continuation(token: String, country: String, language: String): JsonObject? {
        val request = Request.Builder()
            .url("$SEARCH_URL?prettyPrint=false")
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/search")
            .header("Cookie", "SOCS=CAI") // skips the EU consent interstitial
            .post(continuationRequestBody(token, country, language))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string()
            if (!response.isSuccessful || text.isNullOrBlank()) return null
            return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        }
    }

    /**
     * Fetches text completions and directly playable suggestions for a partial [input].
     *
     * Blocking by contract — callers must confine this to an IO dispatcher.
     */
    fun suggestions(input: String, country: String, language: String): JsonObject? {
        val request = Request.Builder()
            .url("$SUGGESTIONS_URL?key=$API_KEY&prettyPrint=false")
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/search")
            .header("Cookie", "SOCS=CAI") // skips the EU consent interstitial
            .post(suggestionsRequestBody(input, country, language))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string()
            if (!response.isSuccessful || text.isNullOrBlank()) return null
            return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        }
    }

    private fun clientContext(country: String, language: String): JsonObject {
        val clientContext = JsonObject().apply {
            addProperty("clientName", "WEB_REMIX")
            addProperty("clientVersion", CLIENT_VERSION)
            addProperty("hl", language)
            addProperty("gl", country)
        }
        return JsonObject().apply { add("client", clientContext) }
    }

    private fun requestBody(query: String, country: String, language: String, params: String?): RequestBody {
        val body = JsonObject().apply {
            add("context", clientContext(country, language))
            addProperty("query", query)
            if (params != null) addProperty("params", params)
        }
        return body.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private fun suggestionsRequestBody(input: String, country: String, language: String): RequestBody {
        val body = JsonObject().apply {
            add("context", clientContext(country, language))
            addProperty("input", input)
        }
        return body.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    private fun continuationRequestBody(token: String, country: String, language: String): RequestBody {
        val body = JsonObject().apply {
            add("context", clientContext(country, language))
            addProperty("continuation", token)
        }
        return body.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    /** WEB_REMIX `params` values for the search category filters, captured from a live search. */
    enum class SearchFilter(val params: String) {
        SONGS("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
        VIDEOS("EgWKAQIQAWoKEAkQBRAKEAMQBA%3D%3D"),
        ALBUMS("EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D"),
        ARTISTS("EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D")
    }

    private companion object {
        const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
        const val SUGGESTIONS_URL = "https://music.youtube.com/youtubei/v1/music/get_search_suggestions"
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
