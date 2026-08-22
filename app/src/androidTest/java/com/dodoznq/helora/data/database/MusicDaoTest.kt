package com.dodoznq.helora.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MusicDaoTest {

    private lateinit var musicDao: MusicDao
    private lateinit var db: HeloraDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, HeloraDatabase::class.java)
            .addCallback(HeloraDatabase.createRuntimeArtifactsCallback())
            .allowMainThreadQueries()
            .build()
        musicDao = db.musicDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    private fun createSongEntity(id: Long, title: String, artist: String, album: String, path: String, genre: String = "Pop"): SongEntity {
        return SongEntity(
            id = id,
            title = title,
            artistName = artist,
            artistId = 101L,
            albumName = album,
            albumId = 201L,
            contentUriString = "uri_$id",
            albumArtUriString = "art_uri_$id",
            duration = 180000,
            genre = genre,
            filePath = path,
            parentDirectoryPath = path.substringBeforeLast("/"),
            year = 2023,
            trackNumber = 1
        )
    }

    private fun createAlbumEntity(id: Long, title: String): AlbumEntity {
        return AlbumEntity(
            id = id,
            title = title,
            artistName = "Artist",
            artistId = 101L,
            albumArtUriString = "art_uri_$id",
            songCount = 5,
            dateAdded = 0L,
            year = 2023
        )
    }

    private fun createArtistEntity(id: Long, name: String): ArtistEntity {
        return ArtistEntity(id = id, name = name, trackCount = 10, imageUrl = null)
    }

    @Test
    @Throws(Exception::class)
    fun insertAndGetSongs() = runTest {
        val songList = listOf(
            createSongEntity(1L, "Song A", "Artist 1", "Album X", "/path/a/songA.mp3"),
            createSongEntity(2L, "Song B", "Artist 2", "Album Y", "/path/b/songB.mp3", "Rock")
        )
        musicDao.insertSongs(songList)

        val retrievedSongs = musicDao.getSongs(emptyList(), false).first()
        assertEquals(2, retrievedSongs.size)
        assertEquals("Song A", retrievedSongs[0].title)
        assertEquals("Song B", retrievedSongs[1].title)
    }

    @Test
    @Throws(Exception::class)
    fun insertAndGetAlbums() = runTest {
        val songs = listOf(
            createSongEntity(1L, "Song A", "Artist 1", "Album X", "/path/a/songA.mp3"),
            createSongEntity(2L, "Song B", "Artist 1", "Album X", "/path/a/songB.mp3")
        )
        musicDao.insertSongs(songs)

        val albumList = listOf(
            createAlbumEntity(201L, "Album X"),
            createAlbumEntity(202L, "Album Y")
        )
        musicDao.insertAlbums(albumList)
        
        val retrievedAlbums = musicDao.getAlbums(emptyList(), false, 0, 1).first()
        
        assertEquals(1, retrievedAlbums.size)
        assertEquals("Album X", retrievedAlbums[0].title)
        assertEquals(2, retrievedAlbums[0].songCount)
    }

    @Test
    @Throws(Exception::class)
    fun insertAndGetArtists() = runTest {
        val song = createSongEntity(1L, "Song A", "Artist 1", "Album X", "/path/a/songA.mp3")
        musicDao.insertSongs(listOf(song))

        val artistList = listOf(
            createArtistEntity(101L, "Artist 1"),
            createArtistEntity(102L, "Artist 2")
        )
        musicDao.insertArtists(artistList)

        val retrievedArtists = musicDao.getArtists(emptyList(), false).first()
        assertEquals(1, retrievedArtists.size)
        assertEquals("Artist 1", retrievedArtists[0].name)
    }

    @Test
    @Throws(Exception::class)
    fun insertMusicData_clearsOldAndInsertsNew() = runTest {
        val oldSong = createSongEntity(1L, "Old Song", "Old Artist", "Old Album", "/old/path/old.mp3")
        musicDao.insertSongs(listOf(oldSong))

        val songs = listOf(
            createSongEntity(10L, "Song A", "Artist 1", "Album X", "/path/a/songA.mp3")
        )
        val albums = listOf(
            createAlbumEntity(201L, "Album X")
        )
        val artists = listOf(
            createArtistEntity(101L, "Artist 1")
        )

        musicDao.insertMusicData(songs, albums, artists)

        val oldSongRetrieved = musicDao.getSongById(1L).first()
        assertNull(oldSongRetrieved)
        
        val newSongRetrieved = musicDao.getSongById(10L).first()
        assertNotNull(newSongRetrieved)
        
        val oldSongStillThere = musicDao.getSongById(1L).first()
        assertNotNull(oldSongStillThere)
    }

    @Test
    @Throws(Exception::class)
    fun searchSongs_returnsMatchingSongs() = runTest {
        val songs = listOf(
            createSongEntity(1L, "Cool Song", "Artist A", "Album X", "/p1/s1.mp3"),
            createSongEntity(2L, "Another Song", "Artist B", "Album Y", "/p2/s2.mp3", "Rock"),
            createSongEntity(3L, "Coolest Song Ever", "Artist C", "Album Z", "/p3/s3.mp3")
        )
        musicDao.insertSongs(songs)

        val results = musicDao.searchSongs("Cool", emptyList(), false).first()
        assertEquals(2, results.size)
        val titles = results.map { it.title }.sorted()
        assertEquals(listOf("Cool Song", "Coolest Song Ever"), titles)
    }
}
