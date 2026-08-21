package com.lostf1sh.pixelplayeross.data.repository

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.network.deezer.DeezerApiService
import com.lostf1sh.pixelplayeross.data.network.deezer.DeezerArtist
import com.lostf1sh.pixelplayeross.data.network.deezer.DeezerSearchResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Most of what matters here is picking the right artist. Deezer carries duplicate entries for
 * the same act and leads with the near-empty ones, so taking the first result answers "no
 * related artists" for acts that plainly have them.
 */
class DeezerRelatedArtistsRepositoryTest {

    private val api: DeezerApiService = mockk()
    private val repository = DeezerRelatedArtistsRepository(api)

    private fun artist(id: Long, name: String, fans: Int = 0) =
        DeezerArtist(id = id, name = name, fanCount = fans)

    private fun stubSearch(vararg candidates: DeezerArtist) {
        coEvery { api.searchArtist(any(), any()) } returns DeezerSearchResponse(candidates.toList())
    }

    private fun stubRelated(artistId: Long, vararg names: String) {
        coEvery { api.getRelatedArtists(artistId) } returns DeezerSearchResponse(
            names.mapIndexed { index, name -> artist(1000L + index, name) }
        )
    }

    @Test
    fun `returns the artists Deezer considers similar`() = runTest {
        stubSearch(artist(14826, "Duman", fans = 200_000))
        stubRelated(14826, "Teoman", "Mor ve Ötesi", "maNga")

        assertThat(repository.relatedArtists("Duman"))
            .containsExactly("Teoman", "Mor ve Ötesi", "maNga").inOrder()
    }

    @Test
    fun `picks the popular entry over the one Deezer returns first`() = runTest {
        // Exactly what the live API does for this name: the first hit has 15 fans and no
        // related artists, the real one has over a million.
        stubSearch(
            artist(382525141, "Sezen Aksu", fans = 15),
            artist(267400002, "Sezen Aksu", fans = 12),
            artist(9289, "Sezen Aksu", fans = 1_184_271)
        )
        stubRelated(9289, "Ajda Pekkan")

        assertThat(repository.relatedArtists("Sezen Aksu")).containsExactly("Ajda Pekkan")
        coVerify(exactly = 0) { api.getRelatedArtists(382525141) }
    }

    @Test
    fun `will not take a more popular artist with a different name`() = runTest {
        // Searching "Tarkan" also returns Reynmen, who has more fans than Tarkan does, so
        // popularity alone would pick the wrong act.
        stubSearch(
            artist(101507802, "Tarkan", fans = 38),
            artist(3254, "Tarkan", fans = 540_103),
            artist(13136341, "Reynmen", fans = 786_574)
        )
        stubRelated(3254, "Mustafa Sandal")

        assertThat(repository.relatedArtists("Tarkan")).containsExactly("Mustafa Sandal")
    }

    @Test
    fun `matches a name whatever its accents and case`() = runTest {
        stubSearch(artist(15049, "Mor ve Ötesi", fans = 299_347))
        stubRelated(15049, "Duman")

        assertThat(repository.relatedArtists("mor ve otesi")).containsExactly("Duman")
    }

    @Test
    fun `leaves the seed artist out of their own related list`() = runTest {
        stubSearch(artist(1, "Duman", fans = 100))
        stubRelated(1, "Teoman", "DUMAN", "maNga")

        assertThat(repository.relatedArtists("Duman")).containsExactly("Teoman", "maNga").inOrder()
    }

    @Test
    fun `returns nothing when no candidate carries the name`() = runTest {
        stubSearch(artist(1, "Role Model", fans = 24_405), artist(2, "Models", fans = 121))

        assertThat(repository.relatedArtists("Model")).isEmpty()
        coVerify(exactly = 0) { api.getRelatedArtists(any()) }
    }

    @Test
    fun `asks once per artist however often the station comes back`() = runTest {
        stubSearch(artist(1, "Duman", fans = 100))
        stubRelated(1, "Teoman")

        repeat(3) { repository.relatedArtists("Duman") }

        coVerify(exactly = 1) { api.getRelatedArtists(1) }
    }

    @Test
    fun `swallows a network failure rather than breaking the station`() = runTest {
        coEvery { api.searchArtist(any(), any()) } throws IOException("offline")

        assertThat(repository.relatedArtists("Duman")).isEmpty()
    }

    @Test
    fun `does not cache a failure, since it says nothing about the artist`() = runTest {
        coEvery { api.searchArtist(any(), any()) } throws IOException("offline")
        assertThat(repository.relatedArtists("Duman")).isEmpty()

        stubSearch(artist(1, "Duman", fans = 100))
        stubRelated(1, "Teoman")

        assertThat(repository.relatedArtists("Duman")).containsExactly("Teoman")
    }

    @Test
    fun `does not search without a name`() = runTest {
        assertThat(repository.relatedArtists(null)).isEmpty()
        assertThat(repository.relatedArtists("   ")).isEmpty()

        coVerify(exactly = 0) { api.searchArtist(any(), any()) }
    }
}
