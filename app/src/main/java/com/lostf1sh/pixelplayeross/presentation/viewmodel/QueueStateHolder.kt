package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.utils.QueueUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages queue shuffle state.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * This class handles the original queue order for shuffle/unshuffle operations.
 */
@Singleton
class QueueStateHolder @Inject constructor() {

    private var _originalQueueOrder: List<Song> = emptyList()
    val originalQueueOrder: List<Song> get() = _originalQueueOrder

    /**
     * Set original queue order (for updates during playback).
     */
    /**
     * Append tracks to the pre-shuffle order, for queues that grow while playing (radio).
     *
     * Appending at the tail is right in both shuffle states, because shuffling here rewrites
     * the queue list rather than using ExoPlayer's shuffle mode: a track added at the end plays
     * next either way, and unshuffling reproduces the same order.
     */
    fun appendToOriginalQueueOrder(songs: List<Song>) {
        if (songs.isEmpty()) return
        val existingIds = _originalQueueOrder.mapTo(HashSet()) { it.id }
        val additions = songs.filter { existingIds.add(it.id) }
        if (additions.isEmpty()) return
        _originalQueueOrder = _originalQueueOrder + additions
    }

    fun setOriginalQueueOrder(queue: List<Song>) {
        _originalQueueOrder = queue.toList()
    }

    /**
     * Check if original queue is empty.
     */
    fun hasOriginalQueue(): Boolean = _originalQueueOrder.isNotEmpty()

    /**
     * Prepares a list for shuffled playback.
     * 1. Saves original queue.
     * 2. Picks a random start song.
     * 3. Creates a shuffled list starting with that song.
     * Runs the heavy shuffle computation on Default dispatcher to avoid UI stalls.
     */
    suspend fun prepareShuffledQueueSuspending(
        songs: List<Song>,
        startAtZero: Boolean = false
    ): Pair<List<Song>, Song>? {
        if (songs.isEmpty()) return null

        val startSong = songs.random()
        setOriginalQueueOrder(songs)

        val startIndex = songs.indexOfFirst { it.id == startSong.id }.coerceAtLeast(0)
        val shuffledQueue = withContext(Dispatchers.Default) {
            QueueUtils.buildAnchoredShuffleQueueSuspending(songs, startIndex, startAtZero)
        }
        return Pair(shuffledQueue, startSong)
    }
}
