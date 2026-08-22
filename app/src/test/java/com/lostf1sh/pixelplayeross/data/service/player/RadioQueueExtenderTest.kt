package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.DeezerRelatedArtistsRepository
import com.lostf1sh.pixelplayeross.data.youtube.RadioPage
import com.lostf1sh.pixelplayeross.data.youtube.RemoteTrackCache
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ConnectivityStateHolder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.QueueStateHolder
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
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

    // --- the fallback ladder ---------------------------------------------------------------
    //
    // A station used to die one round after its mix ran out: the seed artist was the only
    // fallback and it was one shot. These cover the step that follows it.

    private fun fallbackFixture() =
        fixture(queueSize = SHORT_QUEUE, currentIndex = SHORT_QUEUE_INDEX, withStation = false)

    @Test
    fun `carries on with similar artists once the seed artist has nothing left`() = runTest {
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns emptyList()
        coEvery { fixture.relatedArtists.relatedArtists(SEED_ARTIST) } returns listOf("Teoman")
        coEvery { fixture.repository.getSongsForArtist("Teoman") } returns
            List(BATCH_SIZE) { song("teoman-$it", videoId = "t$it") }

        fixture.run()
        advanceUntilIdle()

        // Before this step the round would have ended here with the station marked exhausted.
        // Two per artist, so a round can draw on several similar acts instead of emptying one.
        assertThat(fixture.appended(RELATED_ARTIST_TRACKS))
            .containsExactly("teoman-0", "teoman-1")
            .inOrder()
    }

    @Test
    fun `the seed artist still goes first`() = runTest {
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns
            List(BATCH_SIZE) { song("same-$it", videoId = "s$it") }
        coEvery { fixture.relatedArtists.relatedArtists(any()) } returns listOf("Teoman")

        fixture.run()
        advanceUntilIdle()

        assertThat(fixture.appended(BATCH_SIZE)).containsExactly(
            "same-0", "same-1", "same-2", "same-3", "same-4"
        ).inOrder()
        // Nothing asks Deezer while the seed artist still has something to give.
        coVerify(exactly = 0) { fixture.relatedArtists.relatedArtists(any()) }
    }

    @Test
    fun `a related artist that returns nothing does not end the station`() = runTest {
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns emptyList()
        coEvery { fixture.relatedArtists.relatedArtists(SEED_ARTIST) } returns
            listOf("Nobody", "Teoman")
        coEvery { fixture.repository.getSongsForArtist("Nobody") } returns emptyList()
        coEvery { fixture.repository.getSongsForArtist("Teoman") } returns
            List(BATCH_SIZE) { song("teoman-$it", videoId = "t$it") }

        fixture.run()
        advanceUntilIdle()

        // One name YouTube cannot find should not read as the station having run out.
        assertThat(fixture.appended(RELATED_ARTIST_TRACKS)).contains("teoman-0")
        coVerify { fixture.repository.getSongsForArtist("Nobody") }
    }

    @Test
    fun `gives up when there are no similar artists either`() = runTest {
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns emptyList()
        coEvery { fixture.relatedArtists.relatedArtists(any()) } returns emptyList()

        fixture.run()
        advanceUntilIdle()

        assertThat(fixture.queueStateHolder.originalQueueOrder).hasSize(SHORT_QUEUE)
        coVerify(exactly = 0) { fixture.repository.getSongsForArtist(any()) }
    }

    @Test
    fun `takes only a few tracks from each similar artist`() = runTest {
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns emptyList()
        coEvery { fixture.relatedArtists.relatedArtists(SEED_ARTIST) } returns listOf("Teoman")
        coEvery { fixture.repository.getSongsForArtist("Teoman") } returns
            List(20) { song("teoman-$it", videoId = "t$it") }

        fixture.run()
        advanceUntilIdle()

        // A whole search page would turn the round into one artist's greatest hits.
        assertThat(fixture.queueStateHolder.originalQueueOrder)
            .hasSize(SHORT_QUEUE + RELATED_ARTIST_TRACKS)
        assertThat(fixture.appended(RELATED_ARTIST_TRACKS)).doesNotContain("teoman-5")
    }

    @Test
    fun `an already queued opener does not waste a round`() = runTest {
        val fixture = fallbackFixture()
        val fromSeedArtist = List(BATCH_SIZE) { song("same-$it", videoId = "s$it") }
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns fromSeedArtist
        coEvery { fixture.relatedArtists.relatedArtists(SEED_ARTIST) } returns listOf("Teoman")
        // The related artist leads with tracks the seed artist round already queued, which is
        // ordinary: a search for a band returns their collaborations too.
        coEvery { fixture.repository.getSongsForArtist("Teoman") } returns
            fromSeedArtist + List(RELATED_ARTIST_TRACKS) { song("teoman-$it", videoId = "t$it") }

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        // Trimming before filtering would have left this round with nothing at all.
        assertThat(fixture.appended(RELATED_ARTIST_TRACKS))
            .containsExactly("teoman-0", "teoman-1")
            .inOrder()
    }

    // --- artist spacing ---------------------------------------------------------------------
    //
    // The mix itself is well spaced: YouTube returns the seed artist roughly every fourth
    // track, never more than two together. A fallback round is not, because it answers with
    // one artist's catalogue, and a batch taken straight off the front of that is five songs
    // by the same act back to back.

    @Test
    fun `one artist cannot take over a batch`() = runTest {
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns
            List(5) { song("same-$it", videoId = "s$it", artist = SEED_ARTIST) }
        coEvery { fixture.relatedArtists.relatedArtists(any()) } returns emptyList()

        fixture.run()
        advanceUntilIdle()

        val appended = fixture.queueStateHolder.originalQueueOrder.drop(SHORT_QUEUE)
        // Five by one act back to back is exactly what the user sees without this.
        assertThat(appended).hasSize(MAX_CONSECUTIVE_SAME_ARTIST)
        assertThat(appended.map { it.artist }.distinct()).containsExactly(SEED_ARTIST)
    }

    @Test
    fun `a deferred track is held back rather than dropped`() = runTest {
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns
            List(5) { song("same-$it", videoId = "s$it", artist = SEED_ARTIST) }
        coEvery { fixture.relatedArtists.relatedArtists(any()) } returns emptyList()

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        val ids = fixture.queueStateHolder.originalQueueOrder.map { it.id }
        // Everything still arrives, just spread over more rounds than it would have been.
        assertThat(ids).contains("same-0")
        assertThat(ids).contains("same-2")
    }

    @Test
    fun `spacing never leaves a round empty`() = runTest {
        // A buffer holding nothing but the artist being spaced out must still yield something,
        // or the same songs wait again next round and the station stalls.
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns
            List(6) { song("same-$it", videoId = "s$it", artist = SEED_ARTIST) }
        coEvery { fixture.relatedArtists.relatedArtists(any()) } returns emptyList()

        fixture.run()
        advanceUntilIdle()
        val afterFirst = fixture.queueStateHolder.originalQueueOrder.size
        fixture.topUp()
        advanceUntilIdle()

        assertThat(fixture.queueStateHolder.originalQueueOrder.size).isGreaterThan(afterFirst)
    }

    @Test
    fun `hops onto a similar artist's own mix rather than their popular tracks`() = runTest {
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns emptyList()
        coEvery { fixture.relatedArtists.relatedArtists(SEED_ARTIST) } returns listOf("Teoman")
        // The search exists only to find a track to seed on.
        coEvery { fixture.repository.getSongsForArtist("Teoman") } returns
            listOf(song("teoman-top", videoId = "t-seed", artist = "Teoman"))
        coEvery { fixture.repository.getRadioStation("t-seed") } returns RadioPage(
            songs = listOf(
                song("mix-0", videoId = "m0", artist = "maNga"),
                song("mix-1", videoId = "m1", artist = "Kargo"),
                song("mix-2", videoId = "m2", artist = "Model")
            ),
            nextPage = null,
            stationUrl = "https://example.invalid/teoman"
        )

        fixture.run()
        advanceUntilIdle()

        // Their mix, not their greatest hits: searching a name answers with whatever is
        // biggest, which says nothing about the song the station was built on.
        assertThat(fixture.appended(3)).containsExactly("mix-0", "mix-1", "mix-2").inOrder()
    }

    @Test
    fun `keeps paging the station it hopped to`() = runTest {
        val nextPage = mockk<Page>(relaxed = true)
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns emptyList()
        coEvery { fixture.relatedArtists.relatedArtists(SEED_ARTIST) } returns listOf("Teoman")
        coEvery { fixture.repository.getSongsForArtist("Teoman") } returns
            listOf(song("teoman-top", videoId = "t-seed", artist = "Teoman"))
        coEvery { fixture.repository.getRadioStation("t-seed") } returns RadioPage(
            songs = listOf(song("mix-0", videoId = "m0", artist = "maNga")),
            nextPage = nextPage,
            stationUrl = "https://example.invalid/teoman"
        )
        coEvery {
            fixture.repository.getRadioNextPage("https://example.invalid/teoman", nextPage)
        } returns RadioPage(
            songs = listOf(song("mix-1", videoId = "m1", artist = "Kargo")),
            nextPage = null,
            stationUrl = "https://example.invalid/teoman"
        )

        fixture.run()
        advanceUntilIdle()
        fixture.topUp()
        advanceUntilIdle()

        // The hop re-points the station, so the rest arrives through the ordinary paging path
        // instead of costing another search per round.
        coVerify { fixture.repository.getRadioNextPage("https://example.invalid/teoman", nextPage) }
        assertThat(fixture.queueStateHolder.originalQueueOrder.map { it.id }).contains("mix-1")
    }

    @Test
    fun `falls back to a similar artist's tracks when they have no mix either`() = runTest {
        val fixture = fallbackFixture()
        coEvery { fixture.repository.getArtistFallbackSongs(any()) } returns emptyList()
        coEvery { fixture.relatedArtists.relatedArtists(SEED_ARTIST) } returns listOf("Teoman")
        coEvery { fixture.repository.getSongsForArtist("Teoman") } returns
            List(BATCH_SIZE) { song("teoman-$it", videoId = "t$it", artist = "Teoman") }
        // fallbackFixture leaves getRadioStation returning null for everything.

        fixture.run()
        advanceUntilIdle()

        assertThat(fixture.appended(RELATED_ARTIST_TRACKS))
            .containsExactly("teoman-0", "teoman-1").inOrder()
    }

    private class Fixture(
        val player: Player,
        val queueStateHolder: QueueStateHolder,
        val remoteTrackCache: RemoteTrackCache,
        val repository: YouTubeMusicRepository,
        val relatedArtists: DeezerRelatedArtistsRepository,
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

        val relatedArtists = mockk<DeezerRelatedArtistsRepository>(relaxed = true)

        val repository = mockk<YouTubeMusicRepository>(relaxed = true)
        coEvery { repository.getRadioStation(any()) } returns if (withStation) {
            RadioPage(
                songs = List(STATION_PAGE_SIZE) { song("radio-$it", videoId = "vid$it") },
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
            relatedArtists = relatedArtists,
            extender = RadioQueueExtender(
                repository = repository,
                remoteTrackCache = remoteTrackCache,
                queueStateHolder = queueStateHolder,
                connectivityStateHolder = connectivity,
                relatedArtistsRepository = relatedArtists
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

        /** Matches RadioQueueExtender.RELATED_ARTIST_TRACKS. */
        const val RELATED_ARTIST_TRACKS = 2

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
