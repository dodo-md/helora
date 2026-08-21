package com.lostf1sh.pixelplayeross.data.service.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.youtube.RemoteTrackCache
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ConnectivityStateHolder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.QueueStateHolder
import com.lostf1sh.pixelplayeross.utils.MediaItemBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.schabi.newpipe.extractor.Page
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a radio station topped up as playback approaches the end of the queue.
 *
 * The station is a YouTube Music mix seeded on one track — "more songs like this one" — and it
 * stays **pinned to the track the user chose**. An earlier version re-seeded from whatever was
 * playing, which let the station drift away from the original song over a long session; that is
 * the opposite of what a radio is for.
 *
 * It lives in the service rather than a ViewModel because playback outlives the UI.
 */
@Singleton
class RadioQueueExtender @Inject constructor(
    private val repository: YouTubeMusicRepository,
    private val remoteTrackCache: RemoteTrackCache,
    private val queueStateHolder: QueueStateHolder,
    private val connectivityStateHolder: ConnectivityStateHolder,
) {

    private class Session(
        val token: Long,
        /** The track the station was built from. Never changes for the life of the session. */
        val stationVideoId: String,
        /** Video ids already queued. */
        val seen: LinkedHashSet<String>,
        /** Recording identities already queued, so re-uploads of the same song are skipped. */
        val seenTracks: LinkedHashSet<String> = LinkedHashSet(),
        /** Set once the first page has been fetched; null until then. */
        var stationUrl: String? = null,
        var nextPage: Page? = null,
        /** Fetched but not yet handed to the player, so most top-ups cost no network call. */
        val pending: ArrayDeque<Song> = ArrayDeque(),
        var opened: Boolean = false,
        var exhausted: Boolean = false,
        var artistFallbackUsed: Boolean = false,
        var backoffUntilMs: Long = 0L,
        var consecutiveFailures: Int = 0,
    )

    private val tokenGenerator = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Caps how many art-track searches a single top-up puts in flight at once. */
    private val artTrackSemaphore = Semaphore(ART_TRACK_LOOKUP_CONCURRENCY)

    @Volatile
    private var session: Session? = null
    private var fetchJob: Job? = null

    val isActive: Boolean get() = session != null

    /** Seed of the running station, for persisting into the queue snapshot. */
    val currentSeed: String? get() = session?.stationVideoId

    fun startRadio(seedVideoId: String, initialQueue: List<Song>) {
        val seen = LinkedHashSet<String>()
        val seenTracks = LinkedHashSet<String>()
        initialQueue.forEach { song ->
            song.ytVideoId?.let(seen::add)
            seenTracks.add(YouTubeMusicRepository.trackKey(song.title, song.artist))
        }
        seen.add(seedVideoId)
        session = Session(
            token = tokenGenerator.incrementAndGet(),
            stationVideoId = seedVideoId,
            seen = seen,
            seenTracks = seenTracks
        )
        remoteTrackCache.pin(initialQueue.map { it.id })
        Timber.d("RadioQueueExtender: station armed on %s", seedVideoId)
    }

    /**
     * Re-arms a station for a queue restored from disk, rebuilding the dedup set from the
     * restored items so it does not immediately re-append what is already queued.
     */
    fun restoreRadio(seedVideoId: String, restoredItems: List<MediaItem>) {
        val seen = LinkedHashSet<String>()
        val seenTracks = LinkedHashSet<String>()
        restoredItems.forEach { item ->
            item.mediaMetadata.extras
                ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_YT_VIDEO_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let(seen::add)
            seenTracks.add(
                YouTubeMusicRepository.trackKey(
                    item.mediaMetadata.title?.toString().orEmpty(),
                    item.mediaMetadata.artist?.toString().orEmpty()
                )
            )
        }
        seen.add(seedVideoId)
        session = Session(
            token = tokenGenerator.incrementAndGet(),
            stationVideoId = seedVideoId,
            seen = seen,
            seenTracks = seenTracks
        )
        Timber.d("RadioQueueExtender: station restored on %s", seedVideoId)
    }

    /**
     * Ends the session. Bumping the token first means any fetch already in flight discards its
     * result before touching the player, so a stale batch can never land on a fresh queue.
     */
    fun stopRadio() {
        if (session == null && fetchJob == null) return
        tokenGenerator.incrementAndGet()
        fetchJob?.cancel()
        fetchJob = null
        session = null
        remoteTrackCache.unpinAll()
    }

    /**
     * Called from the service's player listener. Cheap and idempotent: returns immediately
     * unless a station is running and the queue is genuinely running short.
     */
    fun onPlaybackPositionChanged(player: Player) {
        val current = session ?: return
        if (current.exhausted) return
        // REPEAT_ONE never advances toward the end, so extending would grow the queue forever.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
        if (fetchJob?.isActive == true) return
        if (SystemClock.elapsedRealtime() < current.backoffUntilMs) return

        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET) return
        if (player.mediaItemCount - 1 - index > EXTEND_WHEN_REMAINING_AT_MOST) return

        val token = current.token

        // Buffered tracks skip the mix fetch, but appending still resolves each one to its
        // distributed release, so this runs off the main thread like the fetch path does.
        if (current.pending.isNotEmpty()) {
            fetchJob = scope.launch {
                if (token == tokenGenerator.get()) appendFromBuffer(player, current)
            }
            return
        }
        if (!connectivityStateHolder.isOnline.value) return

        fetchJob = scope.launch {
            val fetched = fetchMore(current)
            if (token != tokenGenerator.get()) return@launch

            if (fetched.isEmpty()) {
                current.consecutiveFailures++
                current.backoffUntilMs = SystemClock.elapsedRealtime() +
                    FAILURE_BACKOFF_MS * current.consecutiveFailures.coerceAtMost(MAX_BACKOFF_STEPS)
                return@launch
            }

            current.consecutiveFailures = 0
            current.backoffUntilMs = 0L
            current.pending.addAll(fetched)
            appendFromBuffer(player, current)
        }
    }

    /** Returns new, unseen tracks for the station, or empty when it has nothing left to give. */
    private suspend fun fetchMore(current: Session): List<Song> {
        if (!current.opened) {
            current.opened = true
            val page = repository.getRadioStation(current.stationVideoId)
            if (page != null) {
                current.stationUrl = page.stationUrl
                current.nextPage = page.nextPage
                return page.songs.filterUnseen(current)
            }
            // No mix at all for this track. Fall back to the artist once, then give up: it is
            // still "more like this", just coarser.
            return artistFallback(current)
        }

        val stationUrl = current.stationUrl
        val nextPage = current.nextPage
        if (stationUrl != null && nextPage != null) {
            val page = repository.getRadioNextPage(stationUrl, nextPage)
            if (page != null) {
                current.nextPage = page.nextPage
                val fresh = page.songs.filterUnseen(current)
                if (fresh.isNotEmpty()) return fresh
            }
        }

        return artistFallback(current)
    }

    private suspend fun artistFallback(current: Session): List<Song> {
        if (current.artistFallbackUsed) {
            current.exhausted = true
            return emptyList()
        }
        current.artistFallbackUsed = true
        val seed = remoteTrackCache.getByVideoId(current.stationVideoId)
        if (seed == null) {
            current.exhausted = true
            return emptyList()
        }
        val fallback = repository.getArtistFallbackSongs(seed).filterUnseen(current)
        if (fallback.isEmpty()) current.exhausted = true
        return fallback
    }

    private fun List<Song>.filterUnseen(current: Session): List<Song> = filter { song ->
        val videoId = song.ytVideoId ?: return@filter false
        // Both checks must run as inserts: a track is new only if neither its upload nor its
        // recording identity has been queued before.
        val newUpload = current.seen.add(videoId)
        val newRecording = current.seenTracks.add(
            YouTubeMusicRepository.trackKey(song.title, song.artist)
        )
        newUpload && newRecording
    }

    private suspend fun appendFromBuffer(player: Player, current: Session) {
        val queued = ArrayList<Song>(BATCH_SIZE)
        while (queued.size < BATCH_SIZE && current.pending.isNotEmpty()) {
            queued.add(current.pending.removeFirst())
        }
        if (queued.isEmpty()) return

        // Pin the station to the releases that went to streaming services. Mixes often hand
        // back the music video, which is cut differently and makes synced lyrics drift. A failed
        // lookup keeps the original rather than dropping the track.
        //
        // The lookups run concurrently but bounded: each is a full search round trip, and doing
        // BATCH_SIZE of them end to end can outlast the runway the top-up was scheduled with.
        // The cap is there because YouTube rate-limits aggressive clients, and a 429 comes back
        // as ReCaptchaException rather than a retryable error.
        val resolved = coroutineScope {
            queued.map { song ->
                async {
                    song to artTrackSemaphore.withPermit { repository.resolveArtTrack(song) }
                }
            }.awaitAll()
        }

        // The dedup decision stays sequential and in queue order. Folding it into the parallel
        // block above would let timing decide which upload claims a recording, so the same
        // station could come out in a different order from one run to the next.
        val batch = resolved.map { (song, replacement) ->
            val replacementId = replacement?.ytVideoId
            if (replacementId != null && replacementId != song.ytVideoId &&
                current.seen.add(replacementId)
            ) {
                replacement
            } else {
                song
            }
        }

        remoteTrackCache.putAll(batch)
        remoteTrackCache.pin(batch.map { it.id })
        queueStateHolder.appendToOriginalQueueOrder(batch)

        player.addMediaItems(batch.map(MediaItemBuilder::build))
        trimBehindIfNeeded(player)
        Timber.d("RadioQueueExtender: appended %d track(s)", batch.size)
    }

    /**
     * Drops tracks far behind the current one once the queue grows past [MAX_QUEUE_ITEMS].
     * Without this an endless station would grow the persisted snapshot without bound and make
     * every timeline change more expensive to project into the UI.
     *
     * The trim has to reach everything that shadows the player's queue, or it just moves the
     * growth somewhere else instead of stopping it.
     */
    private fun trimBehindIfNeeded(player: Player) {
        if (player.mediaItemCount <= MAX_QUEUE_ITEMS) return
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET) return
        val removable = index - TRIM_KEEP_BEHIND
        if (removable <= 0) return

        // Collected before the removal, because the indices shift the moment the player drops
        // them. Ids rather than positions: with shuffle on, player order is not the pre-shuffle
        // order, so trimming that list by position would drop tracks still due to play.
        val trimmedIds = (0 until removable).mapNotNullTo(HashSet()) {
            player.getMediaItemAt(it).mediaId.takeIf(String::isNotEmpty)
        }

        // ExoPlayer adjusts currentMediaItemIndex for us.
        player.removeMediaItems(0, removable)

        // Unshuffling rebuilds the queue wholesale from the pre-shuffle order, so leaving the
        // trimmed tracks in it would put them straight back into the player.
        queueStateHolder.removeFromOriginalQueueOrder(trimmedIds)
        // Pinned entries are exempt from eviction, so a station that never releases one pins
        // its whole history and the cache stops evicting altogether.
        remoteTrackCache.unpin(trimmedIds)
    }

    private companion object {
        /** ~3 tracks of runway is comfortably more than one extraction round trip needs. */
        const val EXTEND_WHEN_REMAINING_AT_MOST = 3
        const val BATCH_SIZE = 5

        /**
         * Kept well under BATCH_SIZE so a top-up never fans out the whole batch at YouTube in
         * one go. ytDispatcher already caps at 4, so the effective limit is the lower of the two.
         */
        const val ART_TRACK_LOOKUP_CONCURRENCY = 3
        const val MAX_QUEUE_ITEMS = 400
        const val TRIM_KEEP_BEHIND = 60
        const val FAILURE_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_STEPS = 4
    }
}
