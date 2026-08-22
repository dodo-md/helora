package com.lostf1sh.pixelplayeross.data.youtube

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.Companion.channelIdOrNull
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.Companion.playlistIdOrNull
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.Companion.stripReleaseTypePrefix
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.Companion.artistNameFrom
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.Companion.stripTopicSuffix
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.Companion.cleanTrackTitle
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.Companion.trackKey
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.Companion.musicStationUrl
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.Companion.genericStationUrl
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.Companion.videoIdOrNull
import org.junit.jupiter.api.Test

/**
 * Covers the pure parsing/mapping helpers. The extractor calls themselves are not tested
 * here: they hit the live network and would make the suite flaky.
 */
class YouTubeMusicRepositoryTest {

    @Test
    fun `a stream item is filed under the artist, not the Topic channel`() {
        // Seen live: a radio station hopped onto "Not the King - Topic - Ice Tea", so the
        // suffix was reaching the queue and the now playing screen.
        assertThat(artistNameFrom("Not the King - Topic")).isEqualTo("Not the King")
        assertThat(artistNameFrom("City Girl")).isEqualTo("City Girl")
    }

    @Test
    fun `an unnamed uploader falls back to the artist already known`() {
        assertThat(artistNameFrom(null, "Henyao")).isEqualTo("Henyao")
        assertThat(artistNameFrom("", "Henyao")).isEqualTo("Henyao")
        // A Topic channel with nothing but the suffix is not a name either.
        assertThat(artistNameFrom(" - Topic", "Henyao")).isEqualTo("Henyao")
        assertThat(artistNameFrom(null, "")).isNull()
    }

    @Test
    fun `extracts video ids from watch urls`() {
        assertThat(videoIdOrNull("https://www.youtube.com/watch?v=9RfVp-GhKfs")).isEqualTo("9RfVp-GhKfs")
        assertThat(videoIdOrNull("https://music.youtube.com/watch?v=9RfVp-GhKfs&list=RD"))
            .isEqualTo("9RfVp-GhKfs")
        assertThat(videoIdOrNull("https://youtu.be/9RfVp-GhKfs")).isEqualTo("9RfVp-GhKfs")
    }

    @Test
    fun `rejects malformed video ids`() {
        assertThat(videoIdOrNull(null)).isNull()
        assertThat(videoIdOrNull("")).isNull()
        assertThat(videoIdOrNull("https://www.youtube.com/watch?v=tooshort")).isNull()
        assertThat(videoIdOrNull("https://www.youtube.com/results?search_query=creep")).isNull()
    }

    @Test
    fun `extracts playlist and channel ids`() {
        assertThat(playlistIdOrNull("https://music.youtube.com/playlist?list=OLAK5uy_abc"))
            .isEqualTo("OLAK5uy_abc")
        assertThat(playlistIdOrNull("https://music.youtube.com/browse/xyz")).isNull()
        assertThat(channelIdOrNull("https://music.youtube.com/channel/UCr_iyUANcn9OX_yy9piYoLw"))
            .isEqualTo("UCr_iyUANcn9OX_yy9piYoLw")
        assertThat(channelIdOrNull("https://www.youtube.com/@radiohead")).isNull()
    }

    @Test
    fun `strips youtube generated title decorations`() {
        // YouTube titles album playlists by release type, and auto-generated artist
        // channels by " - Topic". Both would otherwise leak into the UI.
        assertThat(stripReleaseTypePrefix("Album – OK Computer")).isEqualTo("OK Computer")
        assertThat(stripReleaseTypePrefix("Single – Creep")).isEqualTo("Creep")
        assertThat(stripReleaseTypePrefix("EP - Drill")).isEqualTo("Drill")
        assertThat(stripReleaseTypePrefix("OK Computer")).isEqualTo("OK Computer")
        assertThat(stripTopicSuffix("Radiohead - Topic")).isEqualTo("Radiohead")
        assertThat(stripTopicSuffix("Radiohead")).isEqualTo("Radiohead")
    }

    @Test
    fun `song ids are deterministic, negative and per-entity distinct`() {
        // Determinism is what lets an ephemeral track be promoted into Room by INSERT OR
        // REPLACE, keeping any favorite or playlist row written beforehand valid.
        assertThat(YouTubeIds.songId("9RfVp-GhKfs")).isEqualTo(YouTubeIds.songId("9RfVp-GhKfs"))
        assertThat(YouTubeIds.songId("9RfVp-GhKfs")).isLessThan(0L)
        assertThat(YouTubeIds.songId("9RfVp-GhKfs")).isNotEqualTo(YouTubeIds.songId("4BX5xpB2DBM"))
    }

    @Test
    fun `id blocks do not overlap across entity types`() {
        val key = "collision-probe"
        assertThat(setOf(YouTubeIds.songId(key), YouTubeIds.albumId(key), YouTubeIds.artistId(key)))
            .hasSize(3)
    }

    @Test
    fun `ids stay clear of the id blocks used by other cloud sources`() {
        // Netease/GDrive/QQ/Navidrome/Jellyfin occupy 3T..14T; YouTube starts at 15T.
        repeat(500) { index ->
            assertThat(YouTubeIds.songId("video$index")).isLessThan(-15_000_000_000_000L)
        }
    }

    @Test
    fun `station urls carry the mix id and the seed video`() {
        // RDAMVM is the YouTube Music station; RD is the generic fallback for non-music videos.
        assertThat(musicStationUrl("9RfVp-GhKfs"))
            .isEqualTo("https://www.youtube.com/watch?v=9RfVp-GhKfs&list=RDAMVM9RfVp-GhKfs")
        assertThat(genericStationUrl("9RfVp-GhKfs"))
            .isEqualTo("https://www.youtube.com/watch?v=9RfVp-GhKfs&list=RD9RfVp-GhKfs")
    }

    @Test
    fun `strips upload decoration from station track titles`() {
        // Mixes pull in ordinary YouTube uploads, so titles arrive decorated.
        assertThat(cleanTrackTitle("Coldplay - Yellow (Official Video)", "Coldplay"))
            .isEqualTo("Yellow")
        assertThat(cleanTrackTitle("Metallica: Nothing Else Matters", "Metallica"))
            .isEqualTo("Nothing Else Matters")
        // Channel names are not always exactly the artist name.
        assertThat(cleanTrackTitle("The Cranberries - Zombie", "TheCranberriesTV"))
            .isEqualTo("Zombie")
        assertThat(cleanTrackTitle("Numb [Official Music Video] [4K UPGRADE]", "Linkin Park"))
            .isEqualTo("Numb")
    }

    @Test
    fun `keeps meaningful parentheses`() {
        // "(Acoustic)" and "(feat. …)" distinguish real recordings and must survive.
        assertThat(cleanTrackTitle("Creep (Acoustic)", "Radiohead")).isEqualTo("Creep (Acoustic)")
        assertThat(cleanTrackTitle("Stay (feat. Justin Bieber)", "The Kid LAROI"))
            .isEqualTo("Stay (feat. Justin Bieber)")
    }

    @Test
    fun `track key identifies a recording, not an upload`() {
        // A station routinely contains the same song twice under different video ids; the key
        // is what lets the extender drop the duplicate.
        assertThat(trackKey("Where Is My Mind?", "Pixies"))
            .isEqualTo(trackKey("where is my mind", "PIXIES"))
        assertThat(trackKey("Creep", "Radiohead"))
            .isNotEqualTo(trackKey("Creep", "Taylor Swift"))
    }

    @Test
    fun `track key groups album editions of one recording`() {
        // A search for "Numb" returns it once per album edition — Meteora, the anniversary
        // edition, a reissue — all the same recording. The key is what collapses them.
        assertThat(trackKey("Numb", "Linkin Park"))
            .isEqualTo(trackKey("numb", "LINKIN PARK"))
        assertThat(trackKey("Numb", "Linkin Park"))
            .isNotEqualTo(trackKey("Numb / Encore", "Jay-Z"))
    }

    // --- upload decoration vs a different recording -----------------------------------------
    //
    // A station repeats itself when the same song comes round under a second upload title.
    // trackKey is what stops that, and it only works if the decoration is gone from the title
    // first. "Snap (Official Dance Video)" was already handled; the unbracketed forms were not.

    private fun key(rawTitle: String, artist: String = "manifest") =
        YouTubeMusicRepository.trackKey(
            YouTubeMusicRepository.cleanTrackTitle(rawTitle, artist),
            artist
        )

    @Test
    fun `an upload title deduplicates against the plain song however it is decorated`() {
        val plain = key("Snap")

        listOf(
            "Snap (Official Music Video)",
            "Snap (Official Dance Video)",
            "Snap - Official Dance Video",
            "Snap | Official Dance Video",
            "Snap / Official Video",
            "Snap - Special Dance Video",
            "Snap (Special Performance Video)",
            "Snap [Dance Practice]",
            "Snap (Choreography Video)",
            "Snap - Klip",
            "Snap (Visualizer)",
            "Snap (Official Audio)",
            "Snap [HD]",
            "Snap - MV",
            "manifest - Snap"
        ).forEach { decorated ->
            assertThat(key(decorated)).isEqualTo(plain)
        }
    }

    @Test
    fun `a different recording keeps its own identity`() {
        val plain = key("Snap")

        // These name a performance rather than an upload, so collapsing them would hide a
        // genuinely different recording behind one title.
        listOf(
            "Snap (Acoustic)",
            "Snap (Remix)",
            "Snap (feat. Ayla)",
            "Snap - Live at Wembley",
            "Snap (Sped Up)"
        ).forEach { variant ->
            assertThat(key(variant)).isNotEqualTo(plain)
        }
    }

    @Test
    fun `a title that merely contains a noise word is left alone`() {
        assertThat(YouTubeMusicRepository.cleanTrackTitle("Videotape", "Radiohead"))
            .isEqualTo("Videotape")
        assertThat(YouTubeMusicRepository.cleanTrackTitle("Dance Monkey", "Tones and I"))
            .isEqualTo("Dance Monkey")
        assertThat(YouTubeMusicRepository.cleanTrackTitle("Music of the Night", "Various"))
            .isEqualTo("Music of the Night")
    }

    @Test
    fun `stacked decoration comes off in one pass`() {
        assertThat(key("Snap - Official Dance Video - HD")).isEqualTo(key("Snap"))
    }
}
