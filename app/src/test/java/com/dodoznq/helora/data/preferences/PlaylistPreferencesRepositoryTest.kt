package com.dodoznq.helora.data.preferences

import com.dodoznq.helora.data.database.LocalPlaylistDao
import com.dodoznq.helora.data.database.PlaylistEntity
import com.dodoznq.helora.data.database.PlaylistSongEntity
import com.dodoznq.helora.data.database.PlaylistWithSongsEntity
import com.dodoznq.helora.data.model.SMART_PLAYLIST_SOURCE_LEGACY
import com.dodoznq.helora.data.youtube.YouTubeLibraryWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A playlist stores song ids and the screen resolves them back through the `songs` table. A
 * YouTube track that was never downloaded has no row there, so adding one used to write an id
 * that resolved to nothing and the song was missing from the playlist it had just been added
 * to. Promotion has to happen on every path that introduces a new id.
 */
class PlaylistPreferencesRepositoryTest {

    private val dao: LocalPlaylistDao = mockk(relaxed = true)
    private val userPreferences: UserPreferencesRepository = mockk(relaxed = true)
    private val writer: YouTubeLibraryWriter = mockk(relaxed = true)

    // Built lazily: the repository reads the dao flow in its constructor, so the stub has to
    // be in place first.
    private val repository by lazy { PlaylistPreferencesRepository(dao, userPreferences, writer) }

    private val existingPlaylistId = "playlist-1"

    private fun withExistingPlaylist(songIds: List<String> = listOf("11")) {
        // Non-zero keeps the legacy migration from running.
        coEvery { dao.getPlaylistCount() } returns 1
        coEvery { dao.observePlaylistsWithSongs() } returns flowOf(
            listOf(
                PlaylistWithSongsEntity(
                    playlist = PlaylistEntity(id = existingPlaylistId, name = "Mix"),
                    songs = songIds.mapIndexed { index, id ->
                        PlaylistSongEntity(existingPlaylistId, id, index)
                    }
                )
            )
        )
    }

    @Test
    fun `adding songs promotes each new id into the library`() = runTest {
        withExistingPlaylist()

        repository.addSongsToPlaylist(existingPlaylistId, listOf("-15000000000042", "-15000000000043"))

        coVerify(exactly = 1) { writer.promoteById("-15000000000042") }
        coVerify(exactly = 1) { writer.promoteById("-15000000000043") }
    }

    @Test
    fun `the library row is written before the playlist row that points at it`() = runTest {
        withExistingPlaylist()

        repository.addSongsToPlaylist(existingPlaylistId, listOf("-15000000000042"))

        // The other order leaves a window where an observer resolves the id to nothing.
        coVerifyOrder {
            writer.promoteById("-15000000000042")
            dao.replacePlaylistSongs(existingPlaylistId, any())
        }
    }

    @Test
    fun `only the added ids are promoted, not the whole playlist`() = runTest {
        withExistingPlaylist(songIds = listOf("11", "12", "13"))

        repository.addSongsToPlaylist(existingPlaylistId, listOf("-15000000000042"))

        coVerify(exactly = 0) { writer.promoteById("11") }
        coVerify(exactly = 0) { writer.promoteById("12") }
        coVerify(exactly = 0) { writer.promoteById("13") }
    }

    @Test
    fun `creating a playlist from a selection promotes what it starts with`() = runTest {
        withExistingPlaylist()

        repository.createPlaylist(name = "New", songIds = listOf("-15000000000042", "77"))

        // 77 is a MediaStore id and the writer ignores it, but the call still has to be made:
        // the repository cannot tell the two apart, and deciding that is the writer's job.
        coVerify(exactly = 1) { writer.promoteById("-15000000000042") }
        coVerify(exactly = 1) { writer.promoteById("77") }
    }

    @Test
    fun `a smart playlist is left alone`() = runTest {
        coEvery { dao.getPlaylistCount() } returns 1
        coEvery { dao.observePlaylistsWithSongs() } returns flowOf(
            listOf(
                PlaylistWithSongsEntity(
                    playlist = PlaylistEntity(
                        id = existingPlaylistId,
                        name = "Recently added",
                        source = SMART_PLAYLIST_SOURCE_LEGACY
                    ),
                    songs = emptyList()
                )
            )
        )

        repository.addSongsToPlaylist(existingPlaylistId, listOf("-15000000000042"))

        coVerify(exactly = 0) { writer.promoteById(any()) }
    }
}
