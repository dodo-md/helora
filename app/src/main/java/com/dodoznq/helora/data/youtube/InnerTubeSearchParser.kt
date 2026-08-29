package com.dodoznq.helora.data.youtube

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Parses a *filtered* InnerTube `search` response body (one issued with a
 * [InnerTubeSearchClient.SearchFilter] `params` value).
 *
 * An unfiltered search groups results into a `musicCardShelfRenderer` top result plus bare
 * `itemSectionRenderer` blocks with no category label at all, so there is nothing to parse a
 * page out of. A filtered response is different: it carries exactly one `musicShelfRenderer`
 * in `sectionListRenderer.contents`, and that shelf is taken regardless of its title — the
 * filter already pins the category, so matching on the title would be redundant and would
 * make parsing depend on `hl`.
 */
object InnerTubeSearchParser {

    // Not shelf titles here — a search suggestion's song row carries a "Song"/"Video" type
    // descriptor in the same run position a shelf-song's duration or view count would be.
    private const val SONG_TYPE_DESCRIPTOR = "Song"
    private const val VIDEO_TYPE_DESCRIPTOR = "Video"

    private const val ARTIST_CHANNEL_PREFIX = "UC"
    private const val ALBUM_BROWSE_PREFIX = "MPRE"

    private val DURATION_REGEX = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
    private val VIEW_COUNT_REGEX = Regex("^[\\d.,]+[KMB]?\\s*views?$|^no\\s+views$", RegexOption.IGNORE_CASE)

    /** [items] mapped from the shelf's contents, plus its continuation token if it has one. */
    data class ShelfResult<T>(val items: List<T>, val continuation: String?)

    fun parseSongs(root: JsonObject): ShelfResult<InnerTubeSongItem> = parseShelf(root) { it.toSongItem() }

    fun parseAlbums(root: JsonObject): ShelfResult<InnerTubeAlbumItem> = parseShelf(root) { it.toAlbumItem() }

    fun parseArtists(root: JsonObject): ShelfResult<InnerTubeArtistItem> = parseShelf(root) { it.toArtistItem() }

    /**
     * Parses the mixed suggestions response: text completions to show as-is, and directly
     * playable entities mapped with the same [toSongItem] used for shelf songs.
     */
    fun parseSuggestions(root: JsonObject): InnerTubeSuggestions {
        val completions = mutableListOf<String>()
        val songs = mutableListOf<InnerTubeSongItem>()

        for (section in root.getArray("contents") ?: JsonArray()) {
            for (entry in section.asJsonObjectOrNull()
                ?.getObject("searchSuggestionsSectionRenderer")?.getArray("contents") ?: JsonArray()) {
                val entryObject = entry.asJsonObjectOrNull() ?: continue
                entryObject.getObject("searchSuggestionRenderer")?.let { suggestion ->
                    val text = suggestion.path("suggestion", "runs").asJsonArrayOrNull()
                        ?.joinToString("") { it.asJsonObjectOrNull()?.get("text").asStringOrNull().orEmpty() }
                    if (!text.isNullOrBlank()) completions += text
                }
                entry.toSongItem()?.let { songs += it }
            }
        }

        return InnerTubeSuggestions(completions = completions, songs = songs)
    }

    private fun <T> parseShelf(root: JsonObject, mapper: (JsonElement?) -> T?): ShelfResult<T> {
        val shelf = singleMusicShelf(root)
        val items = shelf?.getArray("contents")?.mapNotNull(mapper).orEmpty()
        return ShelfResult(items, shelf?.continuationToken())
    }

    private fun singleMusicShelf(root: JsonObject): JsonObject? =
        shelves(root).firstNotNullOfOrNull { it.asJsonObjectOrNull()?.getObject("musicShelfRenderer") }

    private fun JsonObject.continuationToken(): String? =
        getArray("continuations")?.firstOrNull().asJsonObjectOrNull()
            ?.path("nextContinuationData", "continuation").asStringOrNull()

    /**
     * The shelf lives at different paths depending on whether the response carries search
     * category tabs (the normal shape) or not (some anonymous/region variants inline a single
     * section list) — both are handled defensively since it costs nothing.
     */
    private fun shelves(root: JsonObject): JsonArray {
        root.path("contents", "tabbedSearchResultsRenderer", "tabs")
            .asJsonArrayOrNull()?.firstOrNull()
            ?.path("tabRenderer", "content", "sectionListRenderer", "contents")
            .asJsonArrayOrNull()
            ?.let { return it }

        root.path("contents", "sectionListRenderer", "contents").asJsonArrayOrNull()?.let { return it }

        return JsonArray()
    }

    private fun JsonElement?.toSongItem(): InnerTubeSongItem? {
        val renderer = asJsonObjectOrNull()?.getObject("musicResponsiveListItemRenderer") ?: return null
        val videoId = renderer.extractVideoId() ?: return null
        val flexColumns = renderer.getArray("flexColumns") ?: return null

        val title = flexColumns.firstColumnText() ?: return null

        var artistName: String? = null
        var artistChannelId: String? = null
        var durationMs = 0L

        for (run in flexColumns.getOrNull(1).columnRuns()) {
            val text = run.get("text").asStringOrNull()?.trim().orEmpty()
            if (text.isBlank() || text == SEPARATOR) continue

            val browseId = run.path("navigationEndpoint", "browseEndpoint", "browseId").asStringOrNull()
            when {
                DURATION_REGEX.matches(text) -> durationMs = parseDurationMs(text)
                browseId?.startsWith(ARTIST_CHANNEL_PREFIX) == true && artistName == null -> {
                    artistName = text
                    artistChannelId = browseId
                }
                browseId?.startsWith(ALBUM_BROWSE_PREFIX) == true -> Unit // album — search results use a shared pseudo-album
                artistName == null && !VIEW_COUNT_REGEX.matches(text) &&
                    text != SONG_TYPE_DESCRIPTOR && text != VIDEO_TYPE_DESCRIPTOR ->
                    artistName = text
            }
        }

        return InnerTubeSongItem(
            videoId = videoId,
            title = title,
            artistName = artistName.orEmpty(),
            artistChannelId = artistChannelId,
            durationMs = durationMs,
            thumbnailUrl = renderer.bestThumbnailUrl()
        )
    }

    private fun JsonElement?.toAlbumItem(): InnerTubeAlbumItem? {
        val renderer = asJsonObjectOrNull()?.getObject("musicResponsiveListItemRenderer") ?: return null
        val browseId = renderer.path("navigationEndpoint", "browseEndpoint", "browseId").asStringOrNull()
            ?: return null
        val flexColumns = renderer.getArray("flexColumns") ?: return null
        val title = flexColumns.firstColumnText() ?: return null

        val subtitleRuns = flexColumns.getOrNull(1).columnRuns()
        val artistName = subtitleRuns.firstNotNullOfOrNull { run ->
            val browseId = run.path("navigationEndpoint", "browseEndpoint", "browseId").asStringOrNull()
            val text = run.get("text").asStringOrNull()?.trim().orEmpty()
            text.takeIf { browseId?.startsWith(ARTIST_CHANNEL_PREFIX) == true && it.isNotBlank() }
        }.orEmpty()

        return InnerTubeAlbumItem(
            browseId = browseId,
            title = title,
            artistName = artistName,
            thumbnailUrl = renderer.bestThumbnailUrl()
        )
    }

    private fun JsonElement?.toArtistItem(): InnerTubeArtistItem? {
        val renderer = asJsonObjectOrNull()?.getObject("musicResponsiveListItemRenderer") ?: return null
        val channelId = renderer.path("navigationEndpoint", "browseEndpoint", "browseId").asStringOrNull()
            ?.takeIf { it.startsWith(ARTIST_CHANNEL_PREFIX) } ?: return null
        val name = renderer.getArray("flexColumns")?.firstColumnText() ?: return null

        return InnerTubeArtistItem(
            channelId = channelId,
            name = name,
            thumbnailUrl = renderer.bestThumbnailUrl()
        )
    }

    private fun JsonArray.firstColumnText(): String? =
        getOrNull(0).columnRuns().firstOrNull()?.get("text").asStringOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonElement?.columnRuns(): List<JsonObject> =
        path("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
            .asJsonArrayOrNull()?.mapNotNull { it.asJsonObjectOrNull() }.orEmpty()

    private fun JsonObject.extractVideoId(): String? {
        path("playlistItemData", "videoId").asStringOrNull()?.let { return it }
        path(
            "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
            "playNavigationEndpoint", "watchEndpoint", "videoId"
        ).asStringOrNull()?.let { return it }
        path("navigationEndpoint", "watchEndpoint", "videoId").asStringOrNull()?.let { return it }
        return null
    }

    private fun JsonObject.bestThumbnailUrl(): String? =
        path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
            .asJsonArrayOrNull()?.mapNotNull { it.asJsonObjectOrNull() }
            ?.maxByOrNull { it.get("width").asIntOrNull() ?: 0 }
            ?.get("url").asStringOrNull()

    /** "3:45" / "1:02:03" -> milliseconds. */
    private fun parseDurationMs(text: String): Long {
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return 0L
        var seconds = 0L
        for (part in parts) seconds = seconds * 60 + part
        return seconds * 1000L
    }

    private const val SEPARATOR = "•" // "•"

    // ─── Null-safe JSON tree helpers ──────────────────────────────────────

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? =
        this?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement?.asJsonArrayOrNull(): JsonArray? =
        this?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonElement?.asStringOrNull(): String? =
        this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement?.asIntOrNull(): Int? =
        this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.getObject(key: String): JsonObject? = get(key).asJsonObjectOrNull()

    private fun JsonObject.getArray(key: String): JsonArray? = get(key).asJsonArrayOrNull()

    /** [JsonArray] does not implement [List], so the stdlib `getOrNull` does not apply to it. */
    private fun JsonArray.getOrNull(index: Int): JsonElement? =
        if (index in 0 until size()) get(index) else null

    private fun JsonElement?.path(vararg keys: String): JsonElement? {
        var current: JsonElement? = this
        for (key in keys) current = current.asJsonObjectOrNull()?.get(key)
        return current
    }
}
