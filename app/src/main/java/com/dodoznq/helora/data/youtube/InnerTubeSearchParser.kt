package com.dodoznq.helora.data.youtube

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Parses an InnerTube `search` response body into [InnerTubeSearchPage].
 *
 * YouTube Music's "All" search groups results into shelves labelled by the same category
 * names it shows in its own UI ("Songs", "Videos", "Albums", "Artists", ...). Shelves are
 * matched by that label rather than by position, since position varies with what the query
 * actually matched.
 */
object InnerTubeSearchParser {

    private const val SONGS_SHELF = "Songs"
    private const val VIDEOS_SHELF = "Videos"
    private val ALBUM_SHELVES = setOf("Albums", "Singles", "EPs")
    private const val ARTISTS_SHELF = "Artists"

    private const val ARTIST_CHANNEL_PREFIX = "UC"
    private const val ALBUM_BROWSE_PREFIX = "MPRE"

    private val DURATION_REGEX = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
    private val VIEW_COUNT_REGEX = Regex("^[\\d.,]+[KMB]?\\s*views?$|^no\\s+views$", RegexOption.IGNORE_CASE)

    fun parse(root: JsonObject): InnerTubeSearchPage {
        val songs = mutableListOf<InnerTubeSongItem>()
        val albums = mutableListOf<InnerTubeAlbumItem>()
        val artists = mutableListOf<InnerTubeArtistItem>()

        for (shelf in shelves(root)) {
            val renderer = shelf.asJsonObjectOrNull()?.getObject("musicShelfRenderer") ?: continue
            val title = renderer.path("title", "runs")
                .asJsonArrayOrNull()?.firstOrNull().asJsonObjectOrNull()
                ?.get("text").asStringOrNull().orEmpty()
            val items = renderer.getArray("contents") ?: continue

            when {
                title == SONGS_SHELF || title == VIDEOS_SHELF ->
                    items.mapNotNullTo(songs) { it.toSongItem() }
                title in ALBUM_SHELVES -> items.mapNotNullTo(albums) { it.toAlbumItem() }
                title == ARTISTS_SHELF -> items.mapNotNullTo(artists) { it.toArtistItem() }
            }
        }

        return InnerTubeSearchPage(songs = songs, albums = albums, artists = artists)
    }

    /**
     * The shelves live at different paths depending on whether the response carries search
     * category tabs (the normal "All" search) or not (some anonymous/region variants inline
     * a single section list) — both are handled defensively since it costs nothing.
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
                    text != SONGS_SHELF.removeSuffix("s") && text != VIDEOS_SHELF.removeSuffix("s") ->
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
