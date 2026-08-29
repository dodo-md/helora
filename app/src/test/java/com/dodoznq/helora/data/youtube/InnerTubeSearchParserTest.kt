package com.dodoznq.helora.data.youtube

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test

/**
 * Fixtures mirror the shape of a real InnerTube `search` response closely enough to exercise the
 * parser's field paths, without depending on network access (unavailable in CI/unit tests).
 */
class InnerTubeSearchParserTest {

    private fun parse(json: String): InnerTubeSearchPage =
        InnerTubeSearchParser.parse(JsonParser.parseString(json).asJsonObject)

    /** A shelf entry containing a single `musicResponsiveListItemRenderer`. */
    private fun shelf(title: String, vararg items: String): String =
        """
        {
          "musicShelfRenderer": {
            "title": { "runs": [ { "text": "$title" } ] },
            "contents": [ ${items.joinToString(",")} ]
          }
        }
        """.trimIndent()

    private fun page(vararg shelves: String): String =
        """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [ {
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [ ${shelves.joinToString(",")} ]
                    }
                  }
                }
              } ]
            }
          }
        }
        """.trimIndent()

    private val officialSong = """
        {
          "musicResponsiveListItemRenderer": {
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Godspeed" } ] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [
                  { "text": "Frank Ocean", "navigationEndpoint": { "browseEndpoint": { "browseId": "UCPuFkkoS8xR8U0lVDXQOTvw" } } },
                  { "text": " • " },
                  { "text": "Blonde", "navigationEndpoint": { "browseEndpoint": { "browseId": "MPREb_123456" } } },
                  { "text": " • " },
                  { "text": "3:33" }
              ] } } }
            ],
            "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [
                { "url": "https://example.com/small.jpg", "width": 60, "height": 60 },
                { "url": "https://example.com/large.jpg", "width": 120, "height": 120 }
            ] } } },
            "playlistItemData": { "videoId": "abc12345678" }
          }
        }
    """.trimIndent()

    // The upload this issue is about: a fan-made slowed/reverb edit, which YT Music files
    // under "Videos" rather than "Songs", and which has no album — just an uploader channel.
    private val communityUploadVideo = """
        {
          "musicResponsiveListItemRenderer": {
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Godspeed (Slowed + Reverb)" } ] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [
                  { "text": "Chill Beats Channel", "navigationEndpoint": { "browseEndpoint": { "browseId": "UCzzzzzzzzzzzzzzzzzzzzzz" } } },
                  { "text": " • " },
                  { "text": "148K views" },
                  { "text": " • " },
                  { "text": "3:41" }
              ] } } }
            ],
            "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [
                { "url": "https://example.com/video.jpg", "width": 120, "height": 120 }
            ] } } },
            "overlay": { "musicItemThumbnailOverlayRenderer": { "content": { "musicPlayButtonRenderer": {
                "playNavigationEndpoint": { "watchEndpoint": { "videoId": "xyz98765432" } }
            } } } }
          }
        }
    """.trimIndent()

    private val album = """
        {
          "musicResponsiveListItemRenderer": {
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Blonde" } ] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [
                  { "text": "Album" },
                  { "text": " • " },
                  { "text": "Frank Ocean", "navigationEndpoint": { "browseEndpoint": { "browseId": "UCPuFkkoS8xR8U0lVDXQOTvw" } } },
                  { "text": " • " },
                  { "text": "2016" }
              ] } } }
            ],
            "navigationEndpoint": { "browseEndpoint": { "browseId": "MPREb_123456" } },
            "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [
                { "url": "https://example.com/album.jpg", "width": 120, "height": 120 }
            ] } } }
          }
        }
    """.trimIndent()

    private val artist = """
        {
          "musicResponsiveListItemRenderer": {
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Frank Ocean" } ] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Artist" } ] } } }
            ],
            "navigationEndpoint": { "browseEndpoint": { "browseId": "UCPuFkkoS8xR8U0lVDXQOTvw" } },
            "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [
                { "url": "https://example.com/artist.jpg", "width": 120, "height": 120 }
            ] } } }
          }
        }
    """.trimIndent()

    @Test
    fun `parses an official song from the Songs shelf`() {
        val result = parse(page(shelf("Songs", officialSong)))

        assertThat(result.songs).hasSize(1)
        val song = result.songs.single()
        assertThat(song.videoId).isEqualTo("abc12345678")
        assertThat(song.title).isEqualTo("Godspeed")
        assertThat(song.artistName).isEqualTo("Frank Ocean")
        assertThat(song.artistChannelId).isEqualTo("UCPuFkkoS8xR8U0lVDXQOTvw")
        assertThat(song.durationMs).isEqualTo(213_000L)
        assertThat(song.thumbnailUrl).isEqualTo("https://example.com/large.jpg")
    }

    @Test
    fun `parses a community upload from the Videos shelf, with no album run to confuse it`() {
        val result = parse(page(shelf("Videos", communityUploadVideo)))

        assertThat(result.songs).hasSize(1)
        val video = result.songs.single()
        assertThat(video.videoId).isEqualTo("xyz98765432")
        assertThat(video.title).isEqualTo("Godspeed (Slowed + Reverb)")
        assertThat(video.artistName).isEqualTo("Chill Beats Channel")
        assertThat(video.durationMs).isEqualTo(221_000L)
        // "148K views" must not be mistaken for the artist or the duration.
    }

    @Test
    fun `songs and videos shelves both feed the songs bucket`() {
        val result = parse(page(shelf("Songs", officialSong), shelf("Videos", communityUploadVideo)))

        assertThat(result.songs.map { it.videoId }).containsExactly("abc12345678", "xyz98765432")
    }

    @Test
    fun `parses albums and artists into their own buckets`() {
        val result = parse(page(shelf("Albums", album), shelf("Artists", artist)))

        assertThat(result.albums).hasSize(1)
        val parsedAlbum = result.albums.single()
        assertThat(parsedAlbum.browseId).isEqualTo("MPREb_123456")
        assertThat(parsedAlbum.title).isEqualTo("Blonde")
        assertThat(parsedAlbum.artistName).isEqualTo("Frank Ocean")

        assertThat(result.artists).hasSize(1)
        val parsedArtist = result.artists.single()
        assertThat(parsedArtist.channelId).isEqualTo("UCPuFkkoS8xR8U0lVDXQOTvw")
        assertThat(parsedArtist.name).isEqualTo("Frank Ocean")
    }

    @Test
    fun `an unrecognized shelf is ignored rather than crashing the parse`() {
        val result = parse(page(shelf("Podcasts", officialSong)))

        assertThat(result.songs).isEmpty()
        assertThat(result.albums).isEmpty()
        assertThat(result.artists).isEmpty()
    }

    @Test
    fun `a response with no tabs or shelves parses to an empty page`() {
        val result = parse("{}")

        assertThat(result.songs).isEmpty()
        assertThat(result.albums).isEmpty()
        assertThat(result.artists).isEmpty()
    }

    @Test
    fun `an item missing a video id is dropped rather than crashing`() {
        val brokenItem = """
            {
              "musicResponsiveListItemRenderer": {
                "flexColumns": [
                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "No Id" } ] } } }
                ]
              }
            }
        """.trimIndent()

        val result = parse(page(shelf("Songs", brokenItem)))

        assertThat(result.songs).isEmpty()
    }
}
