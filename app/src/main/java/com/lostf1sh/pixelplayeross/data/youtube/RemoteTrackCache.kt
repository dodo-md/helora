package com.lostf1sh.pixelplayeross.data.youtube

import com.lostf1sh.pixelplayeross.data.model.Song
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory home for tracks that are not (yet) in Room.
 *
 * YouTube results are deliberately ephemeral: a track only earns a Room row once the user
 * favorites it or adds it to a playlist. Everything in between — the search list, the play
 * queue, a radio session — resolves through here, so playback, the queue sheet and the
 * now-playing UI keep working for tracks the database has never seen.
 *
 * Entries are evicted least-recently-used, except those explicitly [pin]ned. Pinning matters:
 * a long radio queue would otherwise evict its own earlier tracks and lose their metadata.
 */
@Singleton
class RemoteTrackCache @Inject constructor() {

    private val entries = Collections.synchronizedMap(
        object : LinkedHashMap<String, Song>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>): Boolean =
                size > MAX_ENTRIES && eldest.key !in pinnedIds
        }
    )

    private val pinnedIds: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun put(song: Song) {
        entries[song.id] = song
    }

    fun putAll(songs: Collection<Song>) {
        if (songs.isEmpty()) return
        synchronized(entries) {
            songs.forEach { entries[it.id] = it }
        }
    }

    fun get(songId: String): Song? = entries[songId]

    fun getByVideoId(videoId: String): Song? = get(YouTubeIds.songId(videoId).toString())

    /** Exempts [songIds] from eviction — used for the tracks currently in the play queue. */
    fun pin(songIds: Collection<String>) {
        pinnedIds.addAll(songIds)
    }

    fun unpinAll() {
        pinnedIds.clear()
    }

    /**
     * Releases specific ids, for tracks the player has dropped from the queue.
     *
     * Eviction stops entirely once the eldest entry is pinned, so a station that pins every
     * track it appends and never releases any would defeat [MAX_ENTRIES] outright.
     */
    fun unpin(songIds: Collection<String>) {
        if (songIds.isEmpty()) return
        pinnedIds.removeAll(songIds.toSet())
    }

    /** Replaces the pinned set in one step, for when a queue is swapped wholesale. */
    fun repin(songIds: Collection<String>) {
        pinnedIds.clear()
        pinnedIds.addAll(songIds)
    }

    private companion object {
        const val MAX_ENTRIES = 600
        const val INITIAL_CAPACITY = 64
        const val LOAD_FACTOR = 0.75f
    }
}
