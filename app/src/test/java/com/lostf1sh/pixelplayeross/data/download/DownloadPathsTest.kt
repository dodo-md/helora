package com.lostf1sh.pixelplayeross.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Path building is the part of downloading that silently corrupts things when wrong: a slash in
 * a track title creates a stray folder, a trailing dot makes two tracks collide, and an
 * over-long name is rejected outright by the filesystem.
 */
class DownloadPathsTest {

    @Test
    fun `strips characters that are illegal in file names`() {
        assertThat(DownloadPaths.sanitizeSegment("AC/DC", "x")).isEqualTo("AC DC")
        assertThat(DownloadPaths.sanitizeSegment("Who?: What*", "x")).isEqualTo("Who What")
        assertThat(DownloadPaths.sanitizeSegment("a\\b|c", "x")).isEqualTo("a b c")
    }

    @Test
    fun `falls back when nothing usable is left`() {
        assertThat(DownloadPaths.sanitizeSegment("", "Unknown")).isEqualTo("Unknown")
        assertThat(DownloadPaths.sanitizeSegment("   ", "Unknown")).isEqualTo("Unknown")
        // "." and ".." would escape or confuse the directory.
        assertThat(DownloadPaths.sanitizeSegment("..", "Unknown")).isEqualTo("Unknown")
        assertThat(DownloadPaths.sanitizeSegment("///", "Unknown")).isEqualTo("Unknown")
    }

    @Test
    fun `drops trailing dots so two tracks cannot collide`() {
        // Some filesystems silently discard a trailing dot, turning "Yes." into "Yes".
        assertThat(DownloadPaths.sanitizeSegment("Yes.", "x")).isEqualTo("Yes")
    }

    @Test
    fun `caps segment length`() {
        val long = "a".repeat(200)
        assertThat(DownloadPaths.sanitizeSegment(long, "x").length).isAtMost(60)
    }

    @Test
    fun `builds a relative path under the public music folder`() {
        assertThat(DownloadPaths.relativePath("Radiohead", "OK Computer"))
            .isEqualTo("Music/Helora/Radiohead/OK Computer/")
        assertThat(DownloadPaths.relativePath("", ""))
            .isEqualTo("Music/Helora/Unknown Artist/Unknown Album/")
    }

    @Test
    fun `numbers file names only when a track number exists`() {
        assertThat(DownloadPaths.fileName("Airbag", 1, "m4a")).isEqualTo("01 - Airbag.m4a")
        assertThat(DownloadPaths.fileName("Airbag", 12, "m4a")).isEqualTo("12 - Airbag.m4a")
        assertThat(DownloadPaths.fileName("Airbag", 0, "m4a")).isEqualTo("Airbag.m4a")
    }
}
