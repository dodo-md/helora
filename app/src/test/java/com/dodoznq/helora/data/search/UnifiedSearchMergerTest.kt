package com.dodoznq.helora.data.search

import com.dodoznq.helora.data.model.SearchResultItem
import com.dodoznq.helora.data.model.Song
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedSearchMergerTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        duration: Long = 180_000L
    ) = Song.emptySong().copy(
        id = id,
        title = title,
        artist = artist,
        duration = duration
    )

    private fun localItem(song: Song): SearchResultItem = SearchResultItem.SongItem(song)

    @Test
    fun `merge returns youtube rows in order when local is empty`() {
        val youtube = listOf(song("yt1", "One", "Artist A"), song("yt2", "Two", "Artist B"))

        val result = UnifiedSearchMerger.merge(local = emptyList(), youtube = youtube)

        assertEquals(2, result.size)
        assertTrue(result.all { it is UnifiedSearchRow.YouTubeRow })
        assertEquals(
            listOf("yt1", "yt2"),
            result.map { (it as UnifiedSearchRow.YouTubeRow).song.id }
        )
    }

    @Test
    fun `merge returns local rows in order when youtube is empty`() {
        val local = listOf(localItem(song("l1", "One", "Artist A")), localItem(song("l2", "Two", "Artist B")))

        val result = UnifiedSearchMerger.merge(local = local, youtube = emptyList())

        assertEquals(2, result.size)
        assertTrue(result.all { it is UnifiedSearchRow.LocalRow })
    }

    @Test
    fun `merge returns an empty list when both sources are empty`() {
        val result = UnifiedSearchMerger.merge(local = emptyList(), youtube = emptyList())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `merge drops a youtube song that exactly matches a local song`() {
        val local = listOf(localItem(song("l1", "Bohemian Rhapsody", "Queen", duration = 355_000L)))
        val youtube = listOf(song("yt1", "Bohemian Rhapsody", "Queen", duration = 355_000L))

        val result = UnifiedSearchMerger.merge(local, youtube)

        assertEquals(1, result.size)
        assertTrue(result.single() is UnifiedSearchRow.LocalRow)
    }

    @Test
    fun `merge drops a youtube song matching a local song despite diacritics`() {
        val local = listOf(localItem(song("l1", "Simarik", "Tarkan", duration = 210_000L)))
        val youtube = listOf(song("yt1", "Simarik", "Tarkân", duration = 210_000L))

        val result = UnifiedSearchMerger.merge(local, youtube)

        assertEquals(1, result.size)
        assertTrue(result.single() is UnifiedSearchRow.LocalRow)
    }

    @Test
    fun `merge drops a youtube song matching a local song despite an Official Video suffix`() {
        val local = listOf(localItem(song("l1", "Halo", "Beyonce", duration = 216_000L)))
        val youtube = listOf(song("yt1", "Halo (Official Video)", "Beyonce", duration = 216_000L))

        val result = UnifiedSearchMerger.merge(local, youtube)

        assertEquals(1, result.size)
        assertTrue(result.single() is UnifiedSearchRow.LocalRow)
    }

    @Test
    fun `merge keeps both rows when title and artist match but duration differs beyond tolerance`() {
        val local = listOf(localItem(song("l1", "Live Forever", "Oasis", duration = 240_000L)))
        val youtube = listOf(song("yt1", "Live Forever", "Oasis", duration = 240_000L + 30_000L))

        val result = UnifiedSearchMerger.merge(local, youtube, durationToleranceMs = 3_000L)

        assertEquals(2, result.size)
        assertTrue(result.any { it is UnifiedSearchRow.LocalRow })
        assertTrue(result.any { it is UnifiedSearchRow.YouTubeRow })
    }

    @Test
    fun `merge keeps a youtube song when title matches but artist differs`() {
        val local = listOf(localItem(song("l1", "Yesterday", "The Beatles", duration = 125_000L)))
        val youtube = listOf(song("yt1", "Yesterday", "Cover Band", duration = 125_000L))

        val result = UnifiedSearchMerger.merge(local, youtube)

        assertEquals(2, result.size)
    }

    @Test
    fun `merge produces unique stableKeys across the merged list`() {
        val local = listOf(
            localItem(song("l1", "One", "Artist A")),
            localItem(song("l2", "Two", "Artist B"))
        )
        val youtube = listOf(song("yt1", "Three", "Artist C"), song("yt2", "Four", "Artist D"))

        val result = UnifiedSearchMerger.merge(local, youtube)

        val keys = result.map { it.stableKey }
        assertEquals(keys.size, keys.toSet().size)
    }
}
