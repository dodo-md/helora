package com.dodoznq.helora.data.service.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.dodoznq.helora.MainCoroutineExtension
import com.dodoznq.helora.data.model.Song
import com.dodoznq.helora.data.youtube.RadioPage
import com.dodoznq.helora.data.youtube.RemoteTrackCache
import com.dodoznq.helora.data.youtube.YouTubeMusicRepository
import com.dodoznq.helora.presentation.viewmodel.ConnectivityStateHolder
import com.dodoznq.helora.presentation.viewmodel.QueueStateHolder
import com.dodoznq.helora.utils.MediaItemBuilder
import org.schabi.newpipe.extractor.Page
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Covers the trim path: the player's queue is shadowed by the pre-shuffle order and by the
 * remote track cache's pin set, and a trim that reaches only the player leaves both of those
 * growing without bound.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class RadioQueueExtenderTest {

    // Chosen so one call satisfies all three gates at once: the queue is past MAX_QUEUE_ITEMS
    // (400), only two tracks remain ahead (<= EXTEND_WHEN_REMAINING_AT_MOST), and there is
    // history to drop beyond TRIM_KEEP_BEHIND (60).
    private val queueSize = 420
    private val currentIndex = 417
    private val expectedTrimCount = currentIndex - 60 // 357

    @BeforeEach
    fun stubMediaItemBuilder() {
        // Real URI construction needs the Android framework: playbackUri() declares a non-null
        // Uri, but Uri.parse returns null under the JVM stubs and the null check throws. What
        // the item carries is irrelevant here as long as the id survives; MediaItemBuilderTest
        // covers the URI rules themselves.
        mockkObject(MediaItemBuilder)
        every { MediaItemBuilder.build(any()) } answers {
            MediaItem.Builder().setMediaId(firstArg<Song>().id).build()
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `trimming the player also trims the pre-shuffle order and releases the pins`() = runTest {
        val fixture = fixture(queueSize = queueSize, currentIndex = currentIndex)

        fixture.run()
        advanceUntilIdle()

        verify { fixture.player.removeMediaItems(0, expectedTrimCount) }

        val remainingIds = fixture.queueStateHolder.originalQueueOrder.map { it.id }

        // Everything the player dropped is gone from the pre-shuffle order too, otherwise
        // unshuffling would put it straight back.
        assertThat(remainingIds).doesNotContain("old-0")
        assertThat(remainingIds).doesNotContain("old-${expectedTrimCount - 1}")

        // The first track past the trim boundary survives, as does the one playing. Losing the
        // current track would make unshuffle bail out entirely.
        assertThat(remainingIds).contains("old-$expectedTrimCount")
        assertThat(remainingIds).contains("old-$currentIndex")
        assertThat(remainingIds).contains("old-${queueSize - 1}")

        // The freshly appended station tracks are there, and nothing else was dropped.
        assertThat(remainingIds).contains("radio-0")
        assertThat(remainingIds).hasSize(queueSize - expectedTrimCount + STATION_PAGE_SIZE)

        verify {
            fixture.remoteTrackCache.unpin(
                match<Collection<String>> {
                    it.size == expectedTrimCount && it.contains("old-0") &&
                        !it.contains("old-$currentIndex")
                }
            )
        }
    }

    @Test
    fun `a queue below the cap is left alone`() = runTest {
        val fixture = fixture(queueSize = 100, currentIndex = 98)

        fixture.run()
        advanceUntilIdle()

        verify(exactly = 0) { fixture.player.removeMediaItems(any(), any()) }
        verify(exactly = 0) { fixture.remoteTrackCache.unpin(any()) }
        assertThat(fixture.queueStateHolder.originalQueueOrder).hasSize(100 + STATION_PAGE_SIZE)
    }

    @Test
    fun `art-track lookups run concurrently but stay capped`() = runTest {
        val fixture = fixture(queueSize = 100, currentIndex = 98)
        // Each lookup is a full search round trip. Virtual time is what separates "one after
        // another" from "all at once" without depending on a wall clock.
        coEvery { fixture.repository.resolveArtTrack(any()) } coAnswers {
            delay(LOOKUP_MS)
            null
        }

        val startedAt = testScheduler.currentTime
        fixture.run()
        advanceUntilIdle()
        val elapsed = testScheduler.currentTime - startedAt

        // End to end, five lookups would cost 5 * LOOKUP_MS.
        assertThat(elapsed).isLessThan(STATION_PAGE_SIZE * LOOKUP_MS)
        // Unbounded fan-out would finish in a single LOOKUP_MS wave. Anything above that means
        // the semaphore is still holding the line, which is what keeps YouTube from 429ing.
        assertThat(elapsed).isAtLeast(2 * LOOKUP_MS)
    }

    @Test
    fun `dedup stays sequential so the batch order is deterministic`() = runTest {
        val fixture = fixture(queueSize = 100, currentIndex = 98)

        // Two station tracks resolve to the same distributed release. Only the first may take
        // it; the second has to keep its own upload rather than duplicating the recording.
        val shared = song("art-shared", videoId = "artshared")
        coEvery { fixture.repository.resolveArtTrack(any()) } answers {
            if (firstArg<Song>().id in setOf("radio-0", "radio-1")) shared else null
        }

        fixture.run()
        advanceUntilIdle()

        val appended = fixture.queueStateHolder.originalQueueOrder.takeLast(STATION_PAGE_SIZE)
        assertThat(appended.map { it.id })
            .containsExactly("art-shared", "radio-1", "radio-2", "radio-3", "radio-4")
            .inOrder()
    }

    // --- staying in the mix ------------------------------------------------------------------
    //
    // Pages overlap heavily deep into a mix and the yield swings. Measured on a lofi seed, the
    // new tracks per page ran 50, 21, 14, 13, 3, 8, 2, 12, 0, 2, 15, and the station kept
    // producing to page 24 for 176 unique tracks. Retiring it at the first zero, which is what
    // this used to do, threw away a third of what it still had.

    private fun stationFixture() =
        fixture(queueSize = SHORT_QUEUE, currentIndex = SHORT_QUEUE_INDEX, withStation = false)

    @Test
    fun `a page with nothing new does not retire the station`() = runTest {
        val second = mockk<Page>(relaxed = true)
        val third = mockk<Page>(relaxed = true)
        val opening = List(BATCH_SIZE) { song("p1-$it", videoId = "a$it", artist = "A$it") }
        val fixture = stationFixture()
        coEvery { fixture.repository.getRadioStation("seed") } returns
            RadioPage(songs = opening, nextPage = second, stationUrl = STATION_URL)
        // Page two hands back only what page one already gave.
        coEvery { fixture.repository.getRadioNextPage(STATION_URL, second) } returns
            RadioPage(songs = opening, nextPage = third, stationUrl = STATION_URL)
        coEvery { fixture.repository.getRadioNextPage(STATION_URL, third) } returns RadioPage(
            songs = List(BATCH_SIZE) { song("p3-$it", videoId = "c$it", artist = "C$it") },
            nextPage = null,
            stationUrl = STATION_URL
        )

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        assertThat(fixture.appended(BATCH_SIZE))
            .containsExactly("p3-0", "p3-1", "p3-2", "p3-3", "p3-4")
            .inOrder()
        // The station is still the station, so nothing below it was ever consulted.
        coVerify(exactly = 0) { fixture.repository.getArtistFallbackSongs(any()) }
    }

    @Test
    fun `the mix is given up on after a run of empty pages`() = runTest {
        val more = mockk<Page>(relaxed = true)
        val opening = List(BATCH_SIZE) { song("p1-$it", videoId = "a$it", artist = "A$it") }
        val fixture = stationFixture()
        coEvery { fixture.repository.getRadioStation("seed") } returns
            RadioPage(songs = opening, nextPage = more, stationUrl = STATION_URL)
        // Every page from here repeats itself, which is what a spent mix actually looks like.
        coEvery { fixture.repository.getRadioNextPage(STATION_URL, more) } returns
            RadioPage(songs = opening, nextPage = more, stationUrl = STATION_URL)

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        // Bounded, or a mix that repeats forever would page forever.
        coVerify(exactly = MAX_EMPTY_PAGES) {
            fixture.repository.getRadioNextPage(STATION_URL, more)
        }
        coVerify { fixture.repository.getArtistFallbackSongs(any()) }
    }

    // --- the hop -------------------------------------------------------------------------------
    //
    // Once a mix is spent the station moves onto the mix of a track it already played, which
    // keeps the whole radio inside YouTube's own recommendations. Measured on a lofi seed paged
    // to exhaustion at 162 tracks, six such pivots each returned a 50 track mix that was 88 to
    // 98 percent unseen, across 28 to 39 artists, all in the same scene.

    @Test
    fun `hops onto the mix of a track the station already played`() = runTest {
        val fixture = fixture(queueSize = SHORT_QUEUE, currentIndex = SHORT_QUEUE_INDEX)
        coEvery { fixture.repository.getRadioStation("vid4") } returns RadioPage(
            songs = listOf(
                song("hop-0", videoId = "h0", artist = "Elior"),
                song("hop-1", videoId = "h1", artist = "Kupla")
            ),
            nextPage = null,
            stationUrl = HOP_URL
        )

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        assertThat(fixture.appended(2)).containsExactly("hop-0", "hop-1").inOrder()
    }

    @Test
    fun `the newest pivot is tried first`() = runTest {
        val fixture = fixture(queueSize = SHORT_QUEUE, currentIndex = SHORT_QUEUE_INDEX)
        coEvery { fixture.repository.getRadioStation("vid4") } returns RadioPage(
            songs = listOf(song("hop-0", videoId = "h0", artist = "Elior")),
            nextPage = null,
            stationUrl = HOP_URL
        )

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        // The tracks a mix opens with sit closest to the seed, and their neighbourhood is the
        // one just exhausted. Starting from the far end is what reaches new material.
        coVerify { fixture.repository.getRadioStation("vid4") }
        coVerify(exactly = 0) { fixture.repository.getRadioStation("vid0") }
    }

    @Test
    fun `only one pivot is kept per artist`() = runTest {
        val fixture = stationFixture()
        coEvery { fixture.repository.getRadioStation("seed") } returns RadioPage(
            songs = List(BATCH_SIZE) { song("p1-$it", videoId = "a$it", artist = "One Act") },
            nextPage = null,
            stationUrl = STATION_URL
        )

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        // Five tracks by one act are one lead to follow, not five, so the round moves on to the
        // seed artist instead of burning its three attempts on the same neighbourhood.
        coVerify(exactly = 1) { fixture.repository.getRadioStation(match { it != "seed" }) }
        coVerify { fixture.repository.getArtistFallbackSongs(any()) }
    }

    @Test
    fun `keeps paging the station it hopped to`() = runTest {
        val hopPage = mockk<Page>(relaxed = true)
        val fixture = fixture(queueSize = SHORT_QUEUE, currentIndex = SHORT_QUEUE_INDEX)
        coEvery { fixture.repository.getRadioStation("vid4") } returns RadioPage(
            songs = listOf(song("hop-0", videoId = "h0", artist = "Elior")),
            nextPage = hopPage,
            stationUrl = HOP_URL
        )
        coEvery { fixture.repository.getRadioNextPage(HOP_URL, hopPage) } returns RadioPage(
            songs = listOf(song("hop-1", videoId = "h1", artist = "Kupla")),
            nextPage = null,
            stationUrl = HOP_URL
        )

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        // The hop re-points the station, so the rest arrives through the ordinary paging path
        // instead of costing another station lookup per round.
        coVerify { fixture.repository.getRadioNextPage(HOP_URL, hopPage) }
        assertThat(fixture.queueStateHolder.originalQueueOrder.map { it.id }).contains("hop-1")
    }

    @Test
    fun `a pivot with no mix does not end the station`() = runTest {
        val fixture = fixture(queueSize = SHORT_QUEUE, currentIndex = SHORT_QUEUE_INDEX)
        coEvery { fixture.repository.getRadioStation("vid4") } returns null
        coEvery { fixture.repository.getRadioStation("vid3") } returns RadioPage(
            songs = listOf(song("hop-0", videoId = "h0", artist = "Elior")),
            nextPage = null,
            stationUrl = HOP_URL
        )

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        assertThat(fixture.queueStateHolder.originalQueueOrder.map { it.id }).contains("hop-0")
    }

    // --- the last resort -----------------------------------------------------------------------

    @Test
    fun `the seed artist is only reached once there is nothing to pivot onto`() = runTest {
        val fixture = fixture(queueSize = SHORT_QUEUE, currentIndex = SHORT_QUEUE_INDEX)
        coEvery { fixture.repository.getRadioStation("vid4") } returns RadioPage(
            songs = listOf(song("hop-0", videoId = "h0", artist = "Elior")),
            nextPage = null,
            stationUrl = HOP_URL
        )

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        // More of the same act is the coarsest thing the radio can do, so it goes last.
        coVerify(exactly = 0) { fixture.repository.getArtistFallbackSongs(any()) }
    }

    @Test
    fun `the seed artist's catalogue is capped rather than spaced`() = runTest {
        val fixture = stationFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns
            List(20) { song("same-$it", videoId = "s$it", artist = SEED_ARTIST) }

        fixture.run()
        advanceUntilIdle()

        // This is the one source that answers with a single act. Taking a whole batch off it is
        // what put five songs by the same artist back to back in the queue.
        assertThat(fixture.queueStateHolder.originalQueueOrder)
            .hasSize(SHORT_QUEUE + SAME_ARTIST_TRACKS)
        assertThat(fixture.appended(SAME_ARTIST_TRACKS))
            .containsExactly("same-0", "same-1")
            .inOrder()
    }

    @Test
    fun `the seed artist is one shot`() = runTest {
        val fixture = stationFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns
            List(20) { song("same-$it", videoId = "s$it", artist = SEED_ARTIST) }

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        // A second round off the same catalogue is just more of that artist, which is the thing
        // the ladder exists to avoid.
        coVerify(exactly = 1) { fixture.repository.getArtistFallbackSongs(any()) }
    }

    @Test
    fun `gives up when there is nothing to pivot onto and no seed artist either`() = runTest {
        val fixture = stationFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns emptyList()

        fixture.run()
        advanceUntilIdle()

        assertThat(fixture.queueStateHolder.originalQueueOrder).hasSize(SHORT_QUEUE)
    }

    // --- artist spacing --------------------------------------------------------------------------
    //
    // A mix is mostly well spaced but not always: over 176 tracks of one station, 150 artists
    // arrived alone, and the rest came in blocks of two, three, four, and one of six.

    @Test
    fun `a full buffer still fills the batch while spacing it`() = runTest {
        // The throttle this catches was live: deferring the offender to the head of the buffer
        // meant it was refused again next round, so the queue grew by one track per top-up and
        // the station could barely stay ahead of the playhead.
        val fixture = stationFixture()
        coEvery { fixture.repository.getRadioStation("seed") } returns RadioPage(
            songs = listOf(
                song("a-0", videoId = "a0", artist = "j^p^n"),
                song("a-1", videoId = "a1", artist = "j^p^n"),
                song("a-2", videoId = "a2", artist = "j^p^n"),
                song("b-0", videoId = "b0", artist = "Saib"),
                song("b-1", videoId = "b1", artist = "Cospe")
            ),
            nextPage = null,
            stationUrl = STATION_URL
        )

        fixture.run()
        advanceUntilIdle()

        val appended = fixture.queueStateHolder.originalQueueOrder.drop(SHORT_QUEUE)
        assertThat(appended).hasSize(BATCH_SIZE)
        assertThat(longestRun(appended.map { it.artist })).isAtMost(MAX_CONSECUTIVE_SAME_ARTIST)
    }

    @Test
    fun `spacing reaches past the head of the buffer to find another artist`() = runTest {
        val fixture = stationFixture()
        coEvery { fixture.repository.getRadioStation("seed") } returns RadioPage(
            songs = listOf(
                song("a-0", videoId = "a0", artist = "j^p^n"),
                song("a-1", videoId = "a1", artist = "j^p^n"),
                song("a-2", videoId = "a2", artist = "j^p^n"),
                song("a-3", videoId = "a3", artist = "j^p^n"),
                song("b-0", videoId = "b0", artist = "Saib")
            ),
            nextPage = null,
            stationUrl = STATION_URL
        )

        fixture.run()
        advanceUntilIdle()

        // The third slot has to be filled from further down the buffer, not from the head.
        val ids = fixture.queueStateHolder.originalQueueOrder.drop(SHORT_QUEUE).map { it.id }
        assertThat(ids.take(3)).containsExactly("a-0", "a-1", "b-0").inOrder()
    }

    @Test
    fun `spacing never leaves a round empty`() = runTest {
        // A buffer holding nothing but the artist being spaced out must still yield something,
        // or the same songs wait again next round and the station stalls.
        val fixture = stationFixture()
        coEvery { fixture.repository.getRadioStation("seed") } returns RadioPage(
            songs = List(6) { song("same-$it", videoId = "s$it", artist = SEED_ARTIST) },
            nextPage = null,
            stationUrl = STATION_URL
        )

        fixture.run()
        advanceUntilIdle()
        val afterFirst = fixture.queueStateHolder.originalQueueOrder.size
        fixture.topUp()
        advanceUntilIdle()

        assertThat(fixture.queueStateHolder.originalQueueOrder.size).isGreaterThan(afterFirst)
    }

    private fun longestRun(values: List<String>): Int {
        var best = 0
        var current = 0
        var previous: String? = null
        values.forEach { value ->
            current = if (value == previous) current + 1 else 1
            previous = value
            if (current > best) best = current
        }
        return best
    }

    private class Fixture(
        val player: Player,
        val queueStateHolder: QueueStateHolder,
        val remoteTrackCache: RemoteTrackCache,
        val repository: YouTubeMusicRepository,
        private val extender: RadioQueueExtender
    ) {
        /** Arms a station and asks for one top-up. Drain with `advanceUntilIdle()`. */
        fun run() {
            extender.startRadio("seed", emptyList())
            extender.onPlaybackPositionChanged(player)
        }

        /**
         * Asks for another top-up. Only reaches the network when the previous round appended
         * something: an empty round arms a backoff that blocks the next one.
         */
        fun topUp() {
            extender.onPlaybackPositionChanged(player)
        }

        /** Ids handed to the queue by the most recent round. */
        fun appended(count: Int): List<String> =
            queueStateHolder.originalQueueOrder.takeLast(count).map { it.id }
    }

    private fun fixture(
        queueSize: Int,
        currentIndex: Int,
        /** False leaves the track with no mix at all, which is what puts the fallback in play. */
        withStation: Boolean = true
    ): Fixture {
        val queueStateHolder = QueueStateHolder()
        queueStateHolder.setOriginalQueueOrder(List(queueSize) { song("old-$it") })

        val remoteTrackCache = mockk<RemoteTrackCache>(relaxed = true)
        // The fallback resolves the seed to find out whose station this is.
        every { remoteTrackCache.getByVideoId("seed") } returns
            song("seed-song", videoId = "seed", artist = SEED_ARTIST)

        val repository = mockk<YouTubeMusicRepository>(relaxed = true)
        coEvery { repository.getRadioStation(any()) } returns if (withStation) {
            RadioPage(
                songs = List(STATION_PAGE_SIZE) {
                    song("radio-$it", videoId = "vid$it", artist = "Artist $it")
                },
                nextPage = null,
                stationUrl = "https://example.invalid/station"
            )
        } else {
            null
        }
        // Keeping the original track makes the appended ids predictable.
        coEvery { repository.resolveArtTrack(any()) } returns null

        val connectivity = mockk<ConnectivityStateHolder>()
        every { connectivity.isOnline } returns MutableStateFlow(true)

        val player = mockk<Player>(relaxed = true)
        every { player.repeatMode } returns Player.REPEAT_MODE_OFF
        every { player.mediaItemCount } returns queueSize
        every { player.currentMediaItemIndex } returns currentIndex
        every { player.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId("old-${firstArg<Int>()}").build()
        }

        return Fixture(
            player = player,
            queueStateHolder = queueStateHolder,
            remoteTrackCache = remoteTrackCache,
            repository = repository,
            extender = RadioQueueExtender(
                repository = repository,
                remoteTrackCache = remoteTrackCache,
                queueStateHolder = queueStateHolder,
                connectivityStateHolder = connectivity
            )
        )
    }

    private fun song(id: String, videoId: String? = null, artist: String = "") =
        Song.emptySong().copy(
            id = id,
            title = id,
            artist = artist,
            ytVideoId = videoId
        )

    private companion object {
        /** Matches RadioQueueExtender.BATCH_SIZE, so one top-up drains the page exactly. */
        const val STATION_PAGE_SIZE = 5

        const val SEED_ARTIST = "Duman"

        /** Matches RadioQueueExtender.SAME_ARTIST_TRACKS. */
        const val SAME_ARTIST_TRACKS = 2

        /** Matches RadioQueueExtender.MAX_EMPTY_PAGES. */
        const val MAX_EMPTY_PAGES = 3

        const val STATION_URL = "https://example.invalid/station"
        const val HOP_URL = "https://example.invalid/hop"

        /** Matches RadioQueueExtender.BATCH_SIZE. */
        const val BATCH_SIZE = 5

        /** Matches RadioQueueExtender.MAX_CONSECUTIVE_SAME_ARTIST. */
        const val MAX_CONSECUTIVE_SAME_ARTIST = 2

        /** Small enough that no trim fires, short enough that a top-up is always due. */
        const val SHORT_QUEUE = 10
        const val SHORT_QUEUE_INDEX = 8

        /** Virtual-time cost of one art-track search. */
        const val LOOKUP_MS = 1_000L
    }
}
