package com.dodoznq.helora.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.dodoznq.helora.data.model.Song
import org.junit.jupiter.api.Test

/**
 * Covers the pre-shuffle order bookkeeping. This list shadows the player's queue, and
 * unshuffling rebuilds the player from it wholesale, so anything the player drops has to
 * leave here too.
 */
class QueueStateHolderTest {

    @Test
    fun `removes only the named ids and keeps the rest in order`() {
        val holder = QueueStateHolder()
        holder.setOriginalQueueOrder(songs("a", "b", "c", "d", "e"))

        holder.removeFromOriginalQueueOrder(setOf("b", "d"))

        assertThat(holder.originalQueueOrder.map { it.id })
            .containsExactly("a", "c", "e")
            .inOrder()
    }

    @Test
    fun `ignores ids that are not queued`() {
        val holder = QueueStateHolder()
        holder.setOriginalQueueOrder(songs("a", "b"))

        holder.removeFromOriginalQueueOrder(setOf("nope"))
        holder.removeFromOriginalQueueOrder(emptySet())

        assertThat(holder.originalQueueOrder.map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `removing everything empties the queue`() {
        val holder = QueueStateHolder()
        holder.setOriginalQueueOrder(songs("a", "b"))

        holder.removeFromOriginalQueueOrder(setOf("a", "b"))

        assertThat(holder.originalQueueOrder).isEmpty()
        assertThat(holder.hasOriginalQueue()).isFalse()
    }

    @Test
    fun `a trimmed track can be appended again`() {
        // appendToOriginalQueueOrder dedupes against what is already queued, so a track that
        // was trimmed and later comes round again must not be swallowed by that check.
        val holder = QueueStateHolder()
        holder.setOriginalQueueOrder(songs("a", "b"))

        holder.removeFromOriginalQueueOrder(setOf("a"))
        holder.appendToOriginalQueueOrder(songs("a"))

        assertThat(holder.originalQueueOrder.map { it.id }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `append then trim leaves the surviving tail`() {
        val holder = QueueStateHolder()
        holder.setOriginalQueueOrder(songs("a", "b"))
        holder.appendToOriginalQueueOrder(songs("c", "d"))

        holder.removeFromOriginalQueueOrder(setOf("a", "b"))

        assertThat(holder.originalQueueOrder.map { it.id }).containsExactly("c", "d").inOrder()
    }

    private fun songs(vararg ids: String): List<Song> =
        ids.map { Song.emptySong().copy(id = it, title = it) }
}
