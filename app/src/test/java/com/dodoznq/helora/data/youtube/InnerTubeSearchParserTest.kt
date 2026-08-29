package com.dodoznq.helora.data.youtube

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test

/**
 * Fixtures under `test/resources/innertube/` are real InnerTube responses, not hand-written
 * JSON: captured live against WEB_REMIX (hl=en, gl=TR, query "radiohead creep") for each of the
 * four category filters, `get_search_suggestions`, and a Songs-shelf continuation (next page),
 * then trimmed to 2-3 items per shelf.
 *
 * The previous fixtures asserted the parser against a `musicShelfRenderer` matched by an
 * English title ("Songs" / "Videos" / ...) inside `tabbedSearchResultsRenderer`. That shape
 * does not exist for an unfiltered search — the live response has a `musicCardShelfRenderer`
 * top result and bare `itemSectionRenderer` blocks with no shelf title at all, so the old
 * fixtures exercised a code path YouTube never actually returns and every unit test passed
 * while search itself returned nothing. A *filtered* search (params set per
 * [InnerTubeSearchClient.SearchFilter]) is what actually carries a single titled
 * `musicShelfRenderer`, which is the shape these fixtures capture.
 */
class InnerTubeSearchParserTest {

    private fun fixture(name: String): JsonObject {
        val stream = javaClass.classLoader?.getResourceAsStream("innertube/$name.json")
            ?: error("Missing test fixture: innertube/$name.json")
        return JsonParser.parseString(stream.bufferedReader().readText()).asJsonObject
    }

    @Test
    fun `parses the Songs shelf from a real filtered search response`() {
        val result = InnerTubeSearchParser.parseSongs(fixture("search_songs"))

        assertThat(result.items).hasSize(3)
        assertThat(result.continuation).isNotEmpty()

        val creep = result.items[0]
        assertThat(creep.videoId).isEqualTo("9RfVp-GhKfs")
        assertThat(creep.title).isEqualTo("Creep")
        assertThat(creep.artistName).isEqualTo("Radiohead")
        assertThat(creep.artistChannelId).isEqualTo("UCr_iyUANcn9OX_yy9piYoLw")
        assertThat(creep.durationMs).isEqualTo(239_000L) // 3:59
        assertThat(creep.thumbnailUrl).contains("w120-h120")
        assertThat(creep.albumTitle).isEqualTo("Creep")
        assertThat(creep.albumBrowseId).isEqualTo("MPREb_TgQPwAzodvg")

        val acoustic = result.items[1]
        assertThat(acoustic.videoId).isEqualTo("4BX5xpB2DBM")
        assertThat(acoustic.title).isEqualTo("Creep (Acoustic)")
        assertThat(acoustic.artistName).isEqualTo("Radiohead")
        assertThat(acoustic.durationMs).isEqualTo(259_000L) // 4:19
        assertThat(acoustic.albumTitle).isEqualTo("Creep EP")
        assertThat(acoustic.albumBrowseId).isEqualTo("MPREb_35HQ88vJ9ck")

        val noSurprises = result.items[2]
        assertThat(noSurprises.videoId).isEqualTo("h1aN7BLHXfc")
        assertThat(noSurprises.title).isEqualTo("No Surprises")
        assertThat(noSurprises.artistName).isEqualTo("Juliana Chahayed")
        assertThat(noSurprises.artistChannelId).isEqualTo("UCVWc7eLT-M_Qjx2YzcYwwiA")
        assertThat(noSurprises.durationMs).isEqualTo(121_000L) // 2:01
        assertThat(noSurprises.albumTitle).isEqualTo("No Surprises")
        assertThat(noSurprises.albumBrowseId).isEqualTo("MPREb_5CwOdQdH8wq")
    }

    @Test
    fun `parses the Videos shelf, whose rows carry a view count instead of an album`() {
        val result = InnerTubeSearchParser.parseSongs(fixture("search_videos"))

        assertThat(result.items).hasSize(3)
        assertThat(result.continuation).isNotEmpty()

        val official = result.items[0]
        assertThat(official.videoId).isEqualTo("XFkzRNyygfk")
        assertThat(official.title).isEqualTo("Creep")
        assertThat(official.artistName).isEqualTo("Radiohead")
        assertThat(official.durationMs).isEqualTo(237_000L) // 3:57
        // "1.5B views" must not be mistaken for the artist, the duration, or an album.
        assertThat(official.albumTitle).isNull()
        assertThat(official.albumBrowseId).isNull()

        val liveCover = result.items[1]
        assertThat(liveCover.videoId).isEqualTo("US0CUegPr3g")
        assertThat(liveCover.title).isEqualTo("Radiohead - Creep (Best live performance)")
        assertThat(liveCover.artistName).isEqualTo("LuigyAguilar02")
        assertThat(liveCover.durationMs).isEqualTo(274_000L) // 4:34
        assertThat(liveCover.albumTitle).isNull()
    }

    @Test
    fun `songs and videos shelves are fetched and parsed independently`() {
        val songs = InnerTubeSearchParser.parseSongs(fixture("search_songs"))
        val videos = InnerTubeSearchParser.parseSongs(fixture("search_videos"))

        assertThat(songs.items.map { it.videoId } + videos.items.map { it.videoId })
            .containsExactly("9RfVp-GhKfs", "4BX5xpB2DBM", "h1aN7BLHXfc", "XFkzRNyygfk", "US0CUegPr3g", "SLbSsv_2u4A")
            .inOrder()
    }

    @Test
    fun `parses a continuation response, a different top-level shape from the first page`() {
        val result = InnerTubeSearchParser.parseSongsContinuation(fixture("search_songs_continuation"))

        assertThat(result.items).hasSize(3)
        assertThat(result.continuation).isNotEmpty()

        val first = result.items[0]
        assertThat(first.videoId).isEqualTo("XX4EpkR-Sp4")
        assertThat(first.title).isEqualTo("Climbing Up the Walls")
        assertThat(first.artistName).isEqualTo("Radiohead")
        assertThat(first.albumTitle).isEqualTo("OK Computer")
        assertThat(first.albumBrowseId).isEqualTo("MPREb_yXhSI4FCUo6")

        assertThat(result.items.map { it.videoId })
            .containsExactly("XX4EpkR-Sp4", "jNY_wLukVW0", "Cj7JDmJ-OKc")
            .inOrder()
    }

    @Test
    fun `parseSongsContinuation on a first-page response finds nothing, the shapes do not overlap`() {
        // musicShelfRenderer (first page) and musicShelfContinuation (next page) are different
        // top-level keys on purpose; a continuation parse must not fall back to the first-page
        // shape, or a bug in the token chain would silently re-show page one forever.
        val result = InnerTubeSearchParser.parseSongsContinuation(fixture("search_songs"))

        assertThat(result.items).isEmpty()
        assertThat(result.continuation).isNull()
    }

    @Test
    fun `parses the Albums shelf, including a multi-artist release with no single artist channel`() {
        val result = InnerTubeSearchParser.parseAlbums(fixture("search_albums"))

        assertThat(result.items).hasSize(3)
        assertThat(result.continuation).isNotEmpty()

        val ep = result.items[0]
        assertThat(ep.browseId).isEqualTo("MPREb_TgQPwAzodvg")
        assertThat(ep.title).isEqualTo("Creep")
        assertThat(ep.artistName).isEqualTo("Radiohead")

        // A various-artists release ("Naeleck, Haley Reinhart, & LNY TNZ") carries no
        // browseId on its subtitle run, since it names several artists rather than one
        // channel. The mapper must not crash or invent an artist for it.
        val compilation = result.items[2]
        assertThat(compilation.browseId).isEqualTo("MPREb_iQi1RivWZ7L")
        assertThat(compilation.title).isEqualTo("Creep (Hardstyle Remix)")
        assertThat(compilation.artistName).isEmpty()
    }

    @Test
    fun `parses the Artists shelf`() {
        val result = InnerTubeSearchParser.parseArtists(fixture("search_artists"))

        assertThat(result.items).hasSize(3)
        // Unlike songs/videos/albums, an Artists shelf carries no continuation in practice
        // (there are rarely more than a handful of matching artist channels).
        assertThat(result.continuation).isNull()

        val radiohead = result.items[0]
        assertThat(radiohead.channelId).isEqualTo("UCr_iyUANcn9OX_yy9piYoLw")
        assertThat(radiohead.name).isEqualTo("Radiohead")

        val thomYorke = result.items[1]
        assertThat(thomYorke.channelId).isEqualTo("UCgXVrNtoMd3CusXrRMX3Tqg")
        assertThat(thomYorke.name).isEqualTo("Thom Yorke")
    }

    @Test
    fun `a response with no shelf parses to an empty result rather than crashing`() {
        val result = InnerTubeSearchParser.parseSongs(JsonParser.parseString("{}").asJsonObject)

        assertThat(result.items).isEmpty()
        assertThat(result.continuation).isNull()
    }

    @Test
    fun `parses search suggestions, both text completions and directly playable songs`() {
        val result = InnerTubeSearchParser.parseSuggestions(fixture("suggestions"))

        assertThat(result.completions).contains("radiohead creep")

        assertThat(result.songs).isNotEmpty()
        val creep = result.songs.first { it.videoId == "9RfVp-GhKfs" }
        assertThat(creep.title).isEqualTo("Creep")
        assertThat(creep.artistName).isEqualTo("Radiohead")
        assertThat(creep.artistChannelId).isEqualTo("UCr_iyUANcn9OX_yy9piYoLw")
        // The suggestion row carries a play count ("2.2B plays") rather than a duration, in
        // the same run position a shelf-song's duration or view count would be — the parser
        // must not mistake "Song" (the row's type descriptor) for the artist name either.
        assertThat(creep.durationMs).isEqualTo(0L)
    }
}
