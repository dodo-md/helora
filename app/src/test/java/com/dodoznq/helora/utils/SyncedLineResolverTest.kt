package com.dodoznq.helora.utils

import com.dodoznq.helora.data.model.SyncedLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncedLineResolverTest {

    private fun line(timeMs: Int) = SyncedLine(time = timeMs, line = "line@$timeMs")

    @Test
    fun `empty list returns -1`() {
        assertEquals(-1, SyncedLineResolver.activeLineIndex(emptyList(), positionMs = 1_000L))
    }

    @Test
    fun `position before first line returns -1`() {
        val lines = listOf(line(1_000), line(2_000), line(3_000))

        assertEquals(-1, SyncedLineResolver.activeLineIndex(lines, positionMs = 500L))
    }

    @Test
    fun `position exactly on a line boundary activates that line`() {
        val lines = listOf(line(1_000), line(2_000), line(3_000))

        assertEquals(1, SyncedLineResolver.activeLineIndex(lines, positionMs = 2_000L))
    }

    @Test
    fun `position in the middle of a line resolves the correct index`() {
        val lines = listOf(line(1_000), line(2_000), line(3_000))

        assertEquals(1, SyncedLineResolver.activeLineIndex(lines, positionMs = 2_500L))
    }

    @Test
    fun `position after the last line returns the last index`() {
        val lines = listOf(line(1_000), line(2_000), line(3_000))

        assertEquals(2, SyncedLineResolver.activeLineIndex(lines, positionMs = 10_000L))
    }

    @Test
    fun `two lines sharing the same time resolve to the later index`() {
        val lines = listOf(line(1_000), line(2_000), line(2_000), line(3_000))

        assertEquals(2, SyncedLineResolver.activeLineIndex(lines, positionMs = 2_000L))
    }

    @Test
    fun `5000-line list resolves correctly via binary search`() {
        val lines = (0 until 5_000).map { index -> line(index * 1_000) }

        assertEquals(2_500, SyncedLineResolver.activeLineIndex(lines, positionMs = 2_500_500L))
        assertEquals(4_999, SyncedLineResolver.activeLineIndex(lines, positionMs = 10_000_000L))
        assertEquals(-1, SyncedLineResolver.activeLineIndex(lines, positionMs = -1L))
    }
}
