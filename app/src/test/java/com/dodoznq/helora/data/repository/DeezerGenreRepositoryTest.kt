package com.dodoznq.helora.data.repository

import com.google.common.truth.Truth.assertThat
import com.dodoznq.helora.data.network.deezer.DeezerAlbum
import com.dodoznq.helora.data.network.deezer.DeezerApiService
import com.dodoznq.helora.data.network.deezer.DeezerArtist
import com.dodoznq.helora.data.network.deezer.DeezerGenre
import com.dodoznq.helora.data.network.deezer.DeezerGenreList
import com.dodoznq.helora.data.network.deezer.DeezerTrack
import com.dodoznq.helora.data.network.deezer.DeezerTrackAlbum
import com.dodoznq.helora.data.network.deezer.DeezerTrackSearchResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * A wrong genre is worse than no genre: it goes into the file tag, where nothing later
 * distinguishes it from one the user set. So most of what matters here is what gets rejected.
 */
class DeezerGenreRepositoryTest {

    private val api: DeezerApiService = mockk()
    private val repository = DeezerGenreRepository(api)

    private fun track(
        artist: String,
        title: String,
        albumId: Long = 1L
    ) = DeezerTrack(
        id = 1L,
        title = title,
        artist = DeezerArtist(id = 1L, name = artist),
        album = DeezerTrackAlbum(id = albumId, title = "Album")
    )

    private fun album(
        vararg genres: Pair<Long, String>,
        primary: Long = genres.firstOrNull()?.first ?: -1L,
        id: Long = 1L
    ) = DeezerAlbum(
        id = id,
        genreId = primary,
        genres = DeezerGenreList(genres.map { DeezerGenre(it.first, it.second) })
    )

    private fun stubSearch(strict: List<DeezerTrack>, loose: List<DeezerTrack> = emptyList()) {
        coEvery { api.searchTrack(match { it.startsWith("artist:") }, any()) } returns
            DeezerTrackSearchResponse(strict)
        coEvery { api.searchTrack(match { !it.startsWith("artist:") }, any()) } returns
            DeezerTrackSearchResponse(loose)
    }

    @Test
    fun `returns the genre of the album the matched track belongs to`() = runTest {
        stubSearch(strict = listOf(track("Radiohead", "Creep")))
        coEvery { api.getAlbum(1L) } returns album(85L to "Alternative")

        assertThat(repository.genreFor("Radiohead", "Creep")).isEqualTo("Alternative")
    }

    @Test
    fun `rejects a different song by the right artist`() = runTest {
        // What a loose Deezer search actually does: it always answers, and the answer for
        // "Zeynep Bastik Aman" is a different track of hers.
        stubSearch(
            strict = emptyList(),
            loose = listOf(track("Zeynep Bastık", "Lan"))
        )

        assertThat(repository.genreFor("Zeynep Bastık", "Aman")).isNull()
        coVerify(exactly = 0) { api.getAlbum(any()) }
    }

    @Test
    fun `rejects the right song by a different artist`() = runTest {
        stubSearch(strict = emptyList(), loose = listOf(track("Postmodern Jukebox", "Creep")))

        assertThat(repository.genreFor("Radiohead", "Creep")).isNull()
    }

    @Test
    fun `falls back to a loose search and accepts a decorated title`() = runTest {
        // Deezer's strict operator misses this one; the loose result carries a suffix the
        // normalizer has to see through.
        stubSearch(
            strict = emptyList(),
            loose = listOf(
                track("Daft Punk", "Get Lucky (Radio Edit - feat. Pharrell Williams)")
            )
        )
        coEvery { api.getAlbum(1L) } returns album(113L to "Dance")

        assertThat(repository.genreFor("Daft Punk", "Get Lucky")).isEqualTo("Dance")
    }

    @Test
    fun `accepts an artist credited with a featured act`() = runTest {
        stubSearch(strict = listOf(track("Sezen Aksu, Sertab Erener", "Gülümse")))
        coEvery { api.getAlbum(1L) } returns album(132L to "Pop")

        assertThat(repository.genreFor("Sezen Aksu", "Gülümse")).isEqualTo("Pop")
    }

    @Test
    fun `prefers the album's primary genre over list order`() = runTest {
        stubSearch(strict = listOf(track("Daft Punk", "Get Lucky")))
        coEvery { api.getAlbum(1L) } returns album(
            106L to "Electro",
            113L to "Dance",
            152L to "Rock",
            primary = 152L
        )

        assertThat(repository.genreFor("Daft Punk", "Get Lucky")).isEqualTo("Rock")
    }

    @Test
    fun `returns null when the album carries no genre`() = runTest {
        stubSearch(strict = listOf(track("Sagopa Kajmer", "Bir Pesimistin Gözyaşları")))
        coEvery { api.getAlbum(1L) } returns album()

        assertThat(repository.genreFor("Sagopa Kajmer", "Bir Pesimistin Gözyaşları")).isNull()
    }

    @Test
    fun `asks for an album once however many of its tracks are downloaded`() = runTest {
        coEvery { api.searchTrack(any(), any()) } returns DeezerTrackSearchResponse(
            listOf(track("Duman", "Her Şeyi Yak"))
        )
        coEvery { api.getAlbum(1L) } returns album(152L to "Rock")

        repeat(3) { repository.genreFor("Duman", "Her Şeyi Yak") }

        // Downloading an album asks the same question once per track.
        coVerify(exactly = 1) { api.getAlbum(1L) }
    }

    @Test
    fun `caches the absence of a genre too`() = runTest {
        coEvery { api.searchTrack(any(), any()) } returns DeezerTrackSearchResponse(
            listOf(track("Müslüm Gürses", "Nilüfer"))
        )
        coEvery { api.getAlbum(1L) } returns album()

        repeat(3) { repository.genreFor("Müslüm Gürses", "Nilüfer") }

        coVerify(exactly = 1) { api.getAlbum(1L) }
    }

    @Test
    fun `swallows a network failure rather than failing the download`() = runTest {
        coEvery { api.searchTrack(any(), any()) } throws IOException("offline")

        assertThat(repository.genreFor("Radiohead", "Creep")).isNull()
    }

    @Test
    fun `does not search without both an artist and a title`() = runTest {
        assertThat(repository.genreFor(null, "Creep")).isNull()
        assertThat(repository.genreFor("Radiohead", null)).isNull()
        assertThat(repository.genreFor("  ", "Creep")).isNull()

        coVerify(exactly = 0) { api.searchTrack(any(), any()) }
    }

    @Test
    fun `normalize folds case accents and decorations`() {
        val normalize = { value: String -> DeezerGenreRepository.normalize(value) }

        assertThat(normalize("Gülümse")).isEqualTo(normalize("Gulumse"))
        assertThat(normalize("HUMBLE.")).isEqualTo(normalize("Humble"))
        assertThat(normalize("mor ve ötesi")).isEqualTo(normalize("Mor ve Ötesi"))
        assertThat(normalize("Zeynep Bastık")).isEqualTo(normalize("Zeynep Bastik"))
        assertThat(normalize("Creep (Official Video)")).isEqualTo(normalize("Creep"))
        assertThat(normalize("Suspus feat. Sagopa")).isEqualTo(normalize("Suspus"))
        assertThat(normalize("Aman")).isNotEqualTo(normalize("Lan"))
    }

    @Test
    fun `normalize refuses containment matches on very short names`() = runTest {
        // "Mor" is a substring of "Mor ve Otesi", which is exactly the kind of accident the
        // minimum length exists to stop.
        stubSearch(strict = emptyList(), loose = listOf(track("Mor ve Ötesi", "Cambaz")))

        assertThat(repository.genreFor("Mor", "Cambaz")).isNull()
    }
}
