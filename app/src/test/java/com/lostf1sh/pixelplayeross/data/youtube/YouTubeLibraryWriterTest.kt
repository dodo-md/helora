package com.lostf1sh.pixelplayeross.data.youtube

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.database.AlbumEntity
import com.lostf1sh.pixelplayeross.data.database.ArtistEntity
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.database.SourceType
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.DeezerGenreRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The library row is what makes a YouTube track visible to the liked list, to listening stats
 * and to the daily mix — all three read through `songs`.
 */
class YouTubeLibraryWriterTest {

    private val dao: MusicDao = mockk(relaxed = true)
    private val cache = RemoteTrackCache()
    private val genreRepository: DeezerGenreRepository = mockk(relaxed = true)
    private val preferences: UserPreferencesRepository = mockk()
    private val writer = YouTubeLibraryWriter(dao, cache, genreRepository, preferences)

    private fun genreLookup(enabled: Boolean) {
        every { preferences.youTubeGenreLookupEnabledFlow } returns flowOf(enabled)
    }

    init {
        genreLookup(enabled = false)
    }

    private fun ytSong(
        videoId: String = "9RfVp-GhKfs",
        title: String = "Creep",
        artist: String = "Radiohead"
    ) = Song.emptySong().copy(
        id = YouTubeIds.songId(videoId).toString(),
        title = title,
        artist = artist,
        artistId = YouTubeIds.artistId(artist),
        album = "Pablo Honey",
        albumId = YouTubeIds.albumId("album-1"),
        contentUriString = "ytmusic://$videoId",
        duration = 238_000L,
        ytVideoId = videoId
    )

    @Test
    fun `writes the song under the id the ephemeral track already carried`() = runTest {
        val song = ytSong()
        val songs = slot<List<SongEntity>>()
        coEvery { dao.insertSongsIgnoreConflicts(capture(songs)) } returns listOf(1L)

        writer.promote(song)

        val written = songs.captured.single()
        // Determinism is the whole point: a favourite written before the row existed already
        // points at this id.
        assertThat(written.id).isEqualTo(YouTubeIds.songId("9RfVp-GhKfs"))
        assertThat(written.id.toString()).isEqualTo(song.id)
        assertThat(written.sourceType).isEqualTo(SourceType.YOUTUBE_MUSIC)
        assertThat(written.contentUriString).isEqualTo("ytmusic://9RfVp-GhKfs")
        assertThat(written.title).isEqualTo("Creep")
        assertThat(written.duration).isEqualTo(238_000L)
    }

    @Test
    fun `writes the artist and album the song row depends on`() = runTest {
        val song = ytSong()
        val artists = slot<List<ArtistEntity>>()
        val albums = slot<List<AlbumEntity>>()
        coEvery { dao.insertArtistsIgnoreConflicts(capture(artists)) } returns listOf(1L)
        coEvery { dao.insertAlbumsIgnoreConflicts(capture(albums)) } returns listOf(1L)

        writer.promote(song)

        // songs has foreign keys onto both, so a missing row here fails the whole insert.
        assertThat(artists.captured.single().id).isEqualTo(song.artistId)
        assertThat(albums.captured.single().id).isEqualTo(song.albumId)
        assertThat(albums.captured.single().artistId).isEqualTo(song.artistId)

        coVerifyOrder(song)
    }

    private suspend fun coVerifyOrder(song: Song) {
        io.mockk.coVerifyOrder {
            dao.insertArtistsIgnoreConflicts(any())
            dao.insertAlbumsIgnoreConflicts(any())
            dao.insertSongsIgnoreConflicts(any())
        }
    }

    @Test
    fun `falls back to placeholders rather than writing blanks`() = runTest {
        val songs = slot<List<SongEntity>>()
        coEvery { dao.insertSongsIgnoreConflicts(capture(songs)) } returns listOf(1L)

        writer.promote(ytSong(artist = "").copy(album = ""))

        val written = songs.captured.single()
        assertThat(written.artistName).isNotEmpty()
        assertThat(written.albumName).isEqualTo(YouTubeMusicRepository.DEFAULT_ALBUM_NAME)
    }

    @Test
    fun `ignores anything that is not a youtube track`() = runTest {
        val local = Song.emptySong().copy(
            id = "42",
            title = "Local file",
            contentUriString = "content://media/external/audio/media/42"
        )
        val navidrome = Song.emptySong().copy(
            id = "-3000000000001",
            title = "Server track",
            contentUriString = "navidrome://abc"
        )

        writer.promote(local)
        writer.promote(navidrome)

        coVerify(exactly = 0) { dao.insertSongsIgnoreConflicts(any()) }
    }

    @Test
    fun `promoteById resolves through the in-flight cache`() = runTest {
        val song = ytSong()
        cache.put(song)
        val songs = slot<List<SongEntity>>()
        coEvery { dao.insertSongsIgnoreConflicts(capture(songs)) } returns listOf(1L)

        writer.promoteById(song.id)

        assertThat(songs.captured.single().id).isEqualTo(YouTubeIds.songId("9RfVp-GhKfs"))
    }

    @Test
    fun `promoteById is a no-op for an id the cache never saw`() = runTest {
        writer.promoteById("-15000000000042")

        coVerify(exactly = 0) { dao.insertSongsIgnoreConflicts(any()) }
    }

    @Test
    fun `leaves the genre empty rather than filing everything under a placeholder`() = runTest {
        val songs = slot<List<SongEntity>>()
        coEvery { dao.insertSongsIgnoreConflicts(capture(songs)) } returns listOf(1L)

        writer.promote(ytSong())

        // The placeholder this replaces put every track the user had ever played into one
        // genre, which also collapsed them onto one player theme colour.
        assertThat(songs.captured.single().genre).isNull()
        coVerify(exactly = 0) { genreRepository.genreFor(any(), any()) }
    }

    @Test
    fun `looks the genre up only once the user turns it on`() = runTest {
        genreLookup(enabled = true)
        coEvery { genreRepository.genreFor("Radiohead", "Creep") } returns "Alternative"
        val songs = slot<List<SongEntity>>()
        coEvery { dao.insertSongsIgnoreConflicts(capture(songs)) } returns listOf(1L)

        writer.promote(ytSong())

        assertThat(songs.captured.single().genre).isEqualTo("Alternative")
    }

    @Test
    fun `still writes no genre when Deezer has no confident answer`() = runTest {
        genreLookup(enabled = true)
        coEvery { genreRepository.genreFor(any(), any()) } returns null
        val songs = slot<List<SongEntity>>()
        coEvery { dao.insertSongsIgnoreConflicts(capture(songs)) } returns listOf(1L)

        writer.promote(ytSong())

        assertThat(songs.captured.single().genre).isNull()
        coVerify(exactly = 0) { dao.fillMissingGenre(any(), any()) }
    }

    @Test
    fun `backfills the genre onto a row that already exists`() = runTest {
        genreLookup(enabled = true)
        coEvery { genreRepository.genreFor(any(), any()) } returns "Alternative"

        writer.promote(ytSong())

        // The insert ignores conflicts, so a track already in the library would otherwise keep
        // its empty genre forever after the toggle is switched on.
        coVerify(exactly = 1) {
            dao.fillMissingGenre(YouTubeIds.songId("9RfVp-GhKfs"), "Alternative")
        }
    }

    @Test
    fun `does not ask Deezer about a track that already has a genre`() = runTest {
        genreLookup(enabled = true)
        val songs = slot<List<SongEntity>>()
        coEvery { dao.insertSongsIgnoreConflicts(capture(songs)) } returns listOf(1L)

        writer.promote(ytSong().copy(genre = "Rock"))

        assertThat(songs.captured.single().genre).isEqualTo("Rock")
        coVerify(exactly = 0) { genreRepository.genreFor(any(), any()) }
    }
}
