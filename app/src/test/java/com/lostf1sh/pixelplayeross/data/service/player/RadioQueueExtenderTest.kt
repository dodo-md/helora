package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.MainCoroutineExtension
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.youtube.RadioPage
import com.lostf1sh.pixelplayeross.data.youtube.RemoteTrackCache
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ConnectivityStateHolder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.QueueStateHolder
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
    }

    private fun fixture(queueSize: Int, currentIndex: Int): Fixture {
        val queueStateHolder = QueueStateHolder()
        queueStateHolder.setOriginalQueueOrder(List(queueSize) { song("old-$it") })

        val remoteTrackCache = mockk<RemoteTrackCache>(relaxed = true)

        val repository = mockk<YouTubeMusicRepository>(relaxed = true)
        coEvery { repository.getRadioStation(any()) } returns RadioPage(
            songs = List(STATION_PAGE_SIZE) { song("radio-$it", videoId = "vid$it") },
            nextPage = null,
            stationUrl = "https://example.invalid/station"
        )
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

    private fun song(id: String, videoId: String? = null) = Song.emptySong().copy(
        id = id,
        title = id,
        ytVideoId = videoId
    )

    private companion object {
        /** Matches RadioQueueExtender.BATCH_SIZE, so one top-up drains the page exactly. */
        const val STATION_PAGE_SIZE = 5

        /** Virtual-time cost of one art-track search. */
        const val LOOKUP_MS = 1_000L
    }
}
