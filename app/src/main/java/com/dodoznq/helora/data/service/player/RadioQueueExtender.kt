package com.dodoznq.helora.data.service.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.dodoznq.helora.data.model.Song
import com.dodoznq.helora.data.youtube.RemoteTrackCache
import com.dodoznq.helora.data.youtube.YouTubeMusicRepository
import com.dodoznq.helora.presentation.viewmodel.ConnectivityStateHolder
import com.dodoznq.helora.presentation.viewmodel.QueueStateHolder
import com.dodoznq.helora.utils.ArtistNameMatching
import com.dodoznq.helora.utils.MediaItemBuilder
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
        /**
         * One track per artist the station has already handed back, newest last. These are the
         * seeds for the next station once this one runs dry: YouTube put them next to the seed
         * itself, so hopping onto one stays inside the same recommendation neighbourhood
         * without anything having to describe what that neighbourhood is.
         */
        val pivots: ArrayDeque<Song> = ArrayDeque(),
        /** Artists already represented in [pivots] or already hopped onto. */
        val pivotArtists: HashSet<String> = HashSet(),
        /** Normalized artist of the last track appended, for spacing across batches. */
        var lastAppendedArtist: String? = null,
        var lastAppendedArtistRun: Int = 0,
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
            // No mix at all for this track. The fallback ladder takes over.
            return fallback(current)
        }

        val stationUrl = current.stationUrl
        if (stationUrl != null) {
            // A page that returns nothing new does not mean the station is spent. Pages overlap
            // heavily deep into a mix and the yield swings: measured on a lofi seed, the new
            // tracks per page ran 50, 21, 14, 13, 3, 8, 2, 12, 0, 2, 15, and kept producing to
            // page 24 for 176 unique tracks. Retiring the station at that first zero, which is
            // what this used to do, threw away a third of what it still had. Five separate zero
            // pages showed up in that run and every one of them recovered.
            var emptyPages = 0
            while (emptyPages < MAX_EMPTY_PAGES) {
                val nextPage = current.nextPage ?: break
                val page = repository.getRadioNextPage(stationUrl, nextPage) ?: break
                current.nextPage = page.nextPage
                val fresh = page.songs.filterUnseen(current)
                if (fresh.isNotEmpty()) return fresh
                emptyPages++
            }
        }

        return fallback(current)
    }

    /**
     * What plays once the mix has nothing left.
     *
     * The station moves onto the mix of a track this station already produced. That keeps the
     * whole radio inside YouTube's own recommendations, which is the strongest similarity
     * signal available here: it is built from what people actually listen to together, so genre
     * and energy carry over without anything having to name them.
     *
     * Measured on a lofi seed whose station was paged to exhaustion at 162 tracks, six such
     * pivots each returned a 50 track mix that was 88 to 98 percent tracks the station had
     * never seen, across 28 to 39 artists, all in the same scene.
     *
     * The seed's own catalogue is the last resort, only for a track that never had a mix at all.
     */
    private suspend fun fallback(current: Session): List<Song> {
        val hopped = hopToNeighbourStation(current)
        if (hopped.isNotEmpty()) return hopped

        if (!current.artistFallbackUsed) {
            current.artistFallbackUsed = true
            val seed = remoteTrackCache.getByVideoId(current.stationVideoId)
            if (seed != null) {
                // Capped, because this is the one source that answers with a single act. Taking
                // a whole batch off it is what put five songs by the same artist back to back.
                val sameArtist = repository.getArtistFallbackSongs(seed)
                    .asSequence()
                    .filterUnseen(current)
                    .take(SAME_ARTIST_TRACKS)
                    .toList()
                if (sameArtist.isNotEmpty()) return sameArtist
            }
        }

        if (current.pivots.isEmpty()) current.exhausted = true
        return emptyList()
    }

    /**
     * Re-points the station at the mix of a track it already played.
     *
     * Pivots are taken newest first. The tracks the station opened with sit closest to the seed
     * and their neighbourhood is the one just exhausted, so starting from the far end is what
     * actually reaches new material.
     *
     * The station is only ever re-pointed once the current mix is spent. Re-seeding on whatever
     * happens to be playing is what an earlier version did, and it let the station wander off
     * the song the user picked.
     */
    private suspend fun hopToNeighbourStation(current: Session): List<Song> {
        var attempts = 0
        while (current.pivots.isNotEmpty() && attempts < HOP_ATTEMPTS_PER_ROUND) {
            val pivot = current.pivots.removeLast()
            val videoId = pivot.ytVideoId ?: continue
            attempts++

            val page = repository.getRadioStation(videoId) ?: continue
            // From here the station pages through this mix exactly like the original one.
            current.stationUrl = page.stationUrl
            current.nextPage = page.nextPage
            val fresh = page.songs.filterUnseen(current)
            if (fresh.isNotEmpty()) {
                Timber.d(
                    "RadioQueueExtender: hopped to %s - %s station, %d new track(s)",
                    pivot.artist,
                    pivot.title,
                    fresh.size
                )
                return fresh
            }
        }
        return emptyList()
    }

    private fun List<Song>.filterUnseen(current: Session): List<Song> =
        asSequence().filterUnseen(current).toList()

    /**
     * Keeps tracks the station has not queued yet, claiming each one it inspects.
     *
     * The claim is the filter: a track counts as new only if neither its upload nor its
     * recording identity has been queued before, and asking marks it as queued. Callers that
     * only want the first few must stay lazy so the rest are never inspected.
     */
    private fun Sequence<Song>.filterUnseen(current: Session): Sequence<Song> = filter { song ->
        val videoId = song.ytVideoId ?: return@filter false
        // Both checks must run as inserts, not short-circuit.
        val newUpload = current.seen.add(videoId)
        val newRecording = current.seenTracks.add(
            YouTubeMusicRepository.trackKey(song.title, song.artist)
        )
        newUpload && newRecording
    }

    private suspend fun appendFromBuffer(player: Player, current: Session) {
        val queued = takeSpacedBatch(current)
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

        batch.forEach { current.rememberPivot(it) }

        remoteTrackCache.putAll(batch)
        remoteTrackCache.pin(batch.map { it.id })
        queueStateHolder.appendToOriginalQueueOrder(batch)

        player.addMediaItems(batch.map(MediaItemBuilder::build))
        trimBehindIfNeeded(player)
        Timber.d("RadioQueueExtender: appended %d track(s)", batch.size)
    }


    /** Records one track per artist as a possible seed for the station after this one. */
    private fun Session.rememberPivot(song: Song) {
        if (song.ytVideoId == null) return
        val artist = ArtistNameMatching.normalize(song.artist)
        if (artist.isEmpty() || !pivotArtists.add(artist)) return
        // Oldest go first: they are the ones nearest the seed, whose neighbourhood is the one
        // the station is working through right now.
        if (pivots.size >= MAX_PIVOTS) pivots.removeFirst()
        pivots.addLast(song)
    }

    /**
     * Takes the next batch, keeping one artist from taking it over.
     *
     * A mix is mostly well spaced but not always: measured over 176 tracks of one station, 150
     * artists arrived alone, and the rest came in blocks of two, three, four, and one of six.
     * Anything over [MAX_CONSECUTIVE_SAME_ARTIST] in a row is passed over for a track further
     * down the buffer, so it still plays, just later.
     *
     * The run is counted from what is already in the queue, not just from this batch, or the
     * seam between two batches would still stack up.
     */
    private fun takeSpacedBatch(current: Session): List<Song> {
        val queued = ArrayList<Song>(BATCH_SIZE)
        var lastArtist = current.lastAppendedArtist
        var run = current.lastAppendedArtistRun

        while (queued.size < BATCH_SIZE && current.pending.isNotEmpty()) {
            // Looking ahead for a different artist is what actually interleaves. Pushing the
            // offender back instead only throttles: it returns to the head of the buffer, is
            // refused again next round, and nothing ever gets in front of it, so the queue
            // grows by one track per top-up while the artist still plays consecutively.
            val index = if (run >= MAX_CONSECUTIVE_SAME_ARTIST && lastArtist != null) {
                current.pending
                    .indexOfFirst { ArtistNameMatching.normalize(it.artist) != lastArtist }
                    .takeIf { it >= 0 }
                    ?: 0
            } else {
                0
            }

            // Falling back to the head when the buffer holds nothing else is deliberate.
            // Spacing reorders, it never withholds, and a round that appends nothing leaves
            // the station short of the playhead.
            val song = current.pending.removeAt(index)
            val artist = ArtistNameMatching.normalize(song.artist)
            val extendsRun = artist.isNotEmpty() && artist == lastArtist
            run = if (extendsRun) run + 1 else 1
            lastArtist = artist
            queued.add(song)
        }

        if (queued.isNotEmpty()) {
            current.lastAppendedArtist = lastArtist
            current.lastAppendedArtistRun = run
        }
        return queued
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

        /**
         * How many pages may come back with nothing new before the mix is treated as spent.
         * Zero pages appear well before the end and recover, so one is far too few; the deepest
         * measured run of consecutive zero pages was one.
         */
        const val MAX_EMPTY_PAGES = 3

        /** Bounds one round's station lookups, so a run of dud pivots cannot stall a top-up. */
        const val HOP_ATTEMPTS_PER_ROUND = 3

        /** Pivot candidates held at once. Enough to outlast any single station. */
        const val MAX_PIVOTS = 60

        /**
         * The seed artist's own catalogue is the only source that answers with a single act, so
         * it is capped rather than spaced. Two matches what may sit together anyway.
         */
        const val SAME_ARTIST_TRACKS = 2

        /**
         * How many tracks by one artist may sit together. Two is enough to let a mix open on
         * the seed artist, which is normal, without a fallback round turning into their
         * greatest hits.
         */
        const val MAX_CONSECUTIVE_SAME_ARTIST = 2
        const val MAX_QUEUE_ITEMS = 400
        const val TRIM_KEEP_BEHIND = 60
        const val FAILURE_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_STEPS = 4
    }
}
