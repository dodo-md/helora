package com.dodoznq.helora.presentation.viewmodel

import app.cash.turbine.test
import com.dodoznq.helora.data.model.SearchFilterType
import com.dodoznq.helora.data.model.Song
import com.dodoznq.helora.data.youtube.RemoteTrackCache
import com.dodoznq.helora.data.youtube.YouTubeMusicRepository
import com.dodoznq.helora.data.youtube.YouTubeSearchNextPage
import com.dodoznq.helora.data.youtube.YouTubeSearchResult
import com.dodoznq.helora.data.youtube.YouTubeStreamProxy
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Covers [YouTubeSearchStateHolder.loadMore], the paging half of YouTube search. The plain
 * search path (debounce, error mapping) predates this and is not re-covered here.
 *
 * `advanceUntilIdle()` does not drive work launched on `backgroundScope` in this project's
 * pinned coroutines-test version — `runCurrent()` does, so every wait here is expressed as an
 * explicit `runCurrent()` (after advancing time past the search debounce where relevant) rather
 * than the usual `advanceUntilIdle()` idiom.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeSearchStateHolderTest {

    /** Advances past the 600ms search debounce, then runs whatever that unblocks. */
    private fun TestScope.drain() {
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
    }

    private fun song(id: String, title: String, artist: String) = Song(
        id = id,
        title = title,
        artist = artist,
        artistId = 1L,
        album = "YouTube Music",
        albumId = 1L,
        path = "",
        contentUriString = "ytmusic://$id",
        albumArtUriString = null,
        duration = 200_000L,
        mimeType = null,
        bitrate = null,
        sampleRate = null,
        ytVideoId = id
    )

    private fun holder(repository: YouTubeMusicRepository): YouTubeSearchStateHolder {
        val connectivityStateHolder = mockk<ConnectivityStateHolder>(relaxed = true)
        every { connectivityStateHolder.isOnline } returns MutableStateFlow(true)
        return YouTubeSearchStateHolder(
            repository = repository,
            streamProxy = mockk<YouTubeStreamProxy>(relaxed = true),
            remoteTrackCache = RemoteTrackCache(),
            connectivityStateHolder = connectivityStateHolder
        )
    }

    @Test
    fun `loadMore appends the next page and dedupes against songs already shown`() = runTest {
        val repository = mockk<YouTubeMusicRepository>(relaxed = true)
        coEvery { repository.search("radiohead") } returns YouTubeSearchResult(
            songs = listOf(song("v1", "Creep", "Radiohead"), song("v2", "Airbag", "Radiohead")),
            songsContinuation = "songs-token-1",
            videosContinuation = null
        )
        coEvery { repository.searchNextPage("songs-token-1", null) } returns YouTubeSearchNextPage(
            // "Creep" reappears under a second video id, the way a real continuation overlapped
            // with page one in a live capture; it must not show up twice.
            songs = listOf(song("v1-dup", "Creep", "Radiohead"), song("v3", "No Surprises", "Radiohead")),
            songsContinuation = "songs-token-2",
            videosContinuation = null
        )
        val holder = holder(repository)
        holder.initialize(backgroundScope)
        testScheduler.runCurrent()

        holder.state.test {
            assertThat(awaitItem()).isEqualTo(YouTubeSearchStateHolder.State())

            holder.performSearch("radiohead", SearchFilterType.ALL)
            drain()
            val afterFirstPage = expectMostRecentItem()
            assertThat(afterFirstPage.songs.map { it.ytVideoId }).containsExactly("v1", "v2").inOrder()
            assertThat(afterFirstPage.hasMoreSongs).isTrue()

            holder.loadMore()
            drain()
            val afterSecondPage = expectMostRecentItem()
            assertThat(afterSecondPage.songs.map { it.ytVideoId }).containsExactly("v1", "v2", "v3").inOrder()
            assertThat(afterSecondPage.songsContinuation).isEqualTo("songs-token-2")
            assertThat(afterSecondPage.isLoadingMore).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadMore is a no-op while a page is already loading`() = runTest {
        val repository = mockk<YouTubeMusicRepository>(relaxed = true)
        coEvery { repository.search("radiohead") } returns YouTubeSearchResult(
            songs = listOf(song("v1", "Creep", "Radiohead")),
            songsContinuation = "songs-token-1"
        )
        val gate = CompletableDeferred<YouTubeSearchNextPage?>()
        coEvery { repository.searchNextPage(any(), any()) } coAnswers { gate.await() }
        val holder = holder(repository)
        holder.initialize(backgroundScope)
        testScheduler.runCurrent()

        holder.performSearch("radiohead", SearchFilterType.ALL)
        drain()

        holder.loadMore()
        holder.loadMore()
        holder.loadMore()
        drain()

        gate.complete(YouTubeSearchNextPage(songs = listOf(song("v2", "Airbag", "Radiohead"))))
        drain()

        coVerify(exactly = 1) { repository.searchNextPage(any(), any()) }
    }

    @Test
    fun `loadMore does nothing once both continuation chains are gone`() = runTest {
        val repository = mockk<YouTubeMusicRepository>(relaxed = true)
        coEvery { repository.search("radiohead") } returns YouTubeSearchResult(
            songs = listOf(song("v1", "Creep", "Radiohead")),
            songsContinuation = null,
            videosContinuation = null
        )
        val holder = holder(repository)
        holder.initialize(backgroundScope)
        testScheduler.runCurrent()

        holder.performSearch("radiohead", SearchFilterType.ALL)
        drain()

        assertThat(holder.state.value.hasMoreSongs).isFalse()

        holder.loadMore()
        drain()

        coVerify(exactly = 0) { repository.searchNextPage(any(), any()) }
    }

    @Test
    fun `a page superseded by a new query is discarded instead of merging into it`() = runTest {
        val repository = mockk<YouTubeMusicRepository>(relaxed = true)
        coEvery { repository.search("radiohead") } returns YouTubeSearchResult(
            songs = listOf(song("v1", "Creep", "Radiohead")),
            songsContinuation = "songs-token-1"
        )
        coEvery { repository.search("muse") } returns YouTubeSearchResult(
            songs = listOf(song("m1", "Hysteria", "Muse")),
            songsContinuation = null
        )
        val stalePage = CompletableDeferred<YouTubeSearchNextPage?>()
        coEvery { repository.searchNextPage("songs-token-1", any()) } coAnswers { stalePage.await() }
        val holder = holder(repository)
        holder.initialize(backgroundScope)
        testScheduler.runCurrent()

        holder.performSearch("radiohead", SearchFilterType.ALL)
        drain()

        // Starts fetching page 2 of "radiohead", but the fetch is held open by stalePage.
        holder.loadMore()
        drain()

        // A new query supersedes it before that page ever arrives.
        holder.performSearch("muse", SearchFilterType.ALL)
        drain()

        // The held-open page 2 (for the abandoned "radiohead" query) finally resolves.
        stalePage.complete(YouTubeSearchNextPage(songs = listOf(song("v2", "Airbag", "Radiohead"))))
        drain()

        val finalState = holder.state.value
        assertThat(finalState.query).isEqualTo("muse")
        assertThat(finalState.songs.map { it.ytVideoId }).containsExactly("m1")
        assertThat(finalState.isLoadingMore).isFalse()
    }

    @Test
    fun `loadMore called during a new query's debounce window does not page the old query`() = runTest {
        val repository = mockk<YouTubeMusicRepository>(relaxed = true)
        coEvery { repository.search("radiohead") } returns YouTubeSearchResult(
            songs = listOf(song("v1", "Creep", "Radiohead")),
            songsContinuation = "songs-token-1"
        )
        coEvery { repository.search("muse") } returns YouTubeSearchResult(
            songs = listOf(song("m1", "Hysteria", "Muse")),
            songsContinuation = null
        )
        val holder = holder(repository)
        holder.initialize(backgroundScope)
        testScheduler.runCurrent()

        holder.performSearch("radiohead", SearchFilterType.ALL)
        drain()
        assertThat(holder.state.value.hasMoreSongs).isTrue()

        // A new query lands and bumps requestId/query right away, but "muse" is still inside
        // its debounce window: repository.search("muse") has not run yet. The old query's
        // continuation token must already be gone, or loadMore() below would page "radiohead"
        // under a state that already reads as "muse".
        holder.performSearch("muse", SearchFilterType.ALL)
        assertThat(holder.state.value.query).isEqualTo("muse")
        assertThat(holder.state.value.hasMoreSongs).isFalse()

        holder.loadMore()
        drain()

        coVerify(exactly = 0) { repository.searchNextPage("songs-token-1", any()) }
        val finalState = holder.state.value
        assertThat(finalState.query).isEqualTo("muse")
        assertThat(finalState.songs.map { it.ytVideoId }).containsExactly("m1")
    }
}
