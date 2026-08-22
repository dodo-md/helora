package com.lostf1sh.pixelplayeross.data.service.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.DeezerRelatedArtistsRepository
import com.lostf1sh.pixelplayeross.data.youtube.RemoteTrackCache
import com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ConnectivityStateHolder
import com.lostf1sh.pixelplayeross.presentation.viewmodel.QueueStateHolder
import com.lostf1sh.pixelplayeross.utils.ArtistNameMatching
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
    private val relatedArtistsRepository: DeezerRelatedArtistsRepository,
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
         * Artists Deezer named as similar to the seed, still to be tried. Null until the mix
         * has actually run out, so a station that never needs them never asks.
         */
        var relatedArtists: ArrayDeque<String>? = null,
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
        val nextPage = current.nextPage
        if (stationUrl != null && nextPage != null) {
            val page = repository.getRadioNextPage(stationUrl, nextPage)
            if (page != null) {
                current.nextPage = page.nextPage
                val fresh = page.songs.filterUnseen(current)
                if (fresh.isNotEmpty()) return fresh
            }
        }

        return fallback(current)
    }

    /**
     * What plays once the mix has nothing left.
     *
     * Two steps, coarsest last. The seed's own artist comes first because it is the closest
     * thing to the station's subject, and it is one shot: a second round would just be more of
     * the same artist. After that the station moves to artists Deezer considers similar, which
     * is what "more like this" actually means once the mix is gone. Before this second step
     * existed the station died after a single fallback round.
     */
    private suspend fun fallback(current: Session): List<Song> {
        val seed = remoteTrackCache.getByVideoId(current.stationVideoId)
        if (seed == null) {
            current.exhausted = true
            return emptyList()
        }

        if (!current.artistFallbackUsed) {
            current.artistFallbackUsed = true
            val sameArtist = repository.getArtistFallbackSongs(seed).filterUnseen(current)
            if (sameArtist.isNotEmpty()) return sameArtist
        }

        return hopToRelatedArtistStation(current, seed)
    }

    /**
     * Moves the station onto a similar artist by opening *their* mix.
     *
     * Searching an artist's name answers with their most popular tracks, which says nothing
     * about the song the station was built on: pivoting from a heavy Duman track to Teoman
     * that way lands on whichever Teoman song is biggest, ballad or not. Their mix is picked
     * the same way the seed's was, by what people actually listen to alongside it, so genre
     * and energy carry over without any of it having to be described.
     *
     * Measured on three pivots from a Turkish rock seed: each mix returned 50 tracks across
     * 33 to 35 artists, the pivot artist taking 26 to 28 percent of them and never more than
     * two in a row, and the neighbours were the same scene rather than that artist's back
     * catalogue.
     *
     * The station is only re-pointed once the original mix is spent. Re-seeding on whatever
     * happens to be playing is what an earlier version did, and it let the station wander off
     * the song the user picked.
     */
    private suspend fun hopToRelatedArtistStation(current: Session, seed: Song): List<Song> {
        val queue = current.relatedArtists ?: run {
            val names = relatedArtistsRepository.relatedArtists(seed.artist)
            Timber.d("RadioQueueExtender: %d related artist(s) for %s", names.size, seed.artist)
            ArrayDeque(names).also { current.relatedArtists = it }
        }

        var attempts = 0
        while (queue.isNotEmpty() && attempts < RELATED_ARTIST_ATTEMPTS_PER_ROUND) {
            attempts++
            val artist = queue.removeFirst()

            // One search, only to find a track of theirs to seed on. The results are already
            // verified to be by this artist, so any of them will do.
            val pivot = repository.getSongsForArtist(artist).firstOrNull()?.ytVideoId
            if (pivot == null) {
                Timber.d("RadioQueueExtender: no track found for related artist %s", artist)
                continue
            }

            val page = repository.getRadioStation(pivot)
            if (page != null) {
                // From here the station pages through their mix exactly like the original one.
                current.stationUrl = page.stationUrl
                current.nextPage = page.nextPage
                val fresh = page.songs.filterUnseen(current)
                if (fresh.isNotEmpty()) {
                    Timber.d(
                        "RadioQueueExtender: hopped to %s station, %d new track(s)",
                        artist,
                        fresh.size
                    )
                    return fresh
                }
            }

            // No mix for them either. Their own tracks are still closer than nothing, and the
            // queue spacing keeps them from arriving as a block.
            val songs = repository.getSongsForArtist(artist)
                .asSequence()
                .filterUnseen(current)
                .take(RELATED_ARTIST_TRACKS)
                .toList()
            if (songs.isNotEmpty()) {
                Timber.d("RadioQueueExtender: %d track(s) by related artist %s", songs.size, artist)
                return songs
            }
        }

        if (queue.isEmpty()) current.exhausted = true
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

        remoteTrackCache.putAll(batch)
        remoteTrackCache.pin(batch.map { it.id })
        queueStateHolder.appendToOriginalQueueOrder(batch)

        player.addMediaItems(batch.map(MediaItemBuilder::build))
        trimBehindIfNeeded(player)
        Timber.d("RadioQueueExtender: appended %d track(s)", batch.size)
    }


    /**
     * Takes the next batch, keeping one artist from taking it over.
     *
     * The mix itself is well spaced, but a fallback round is not: it answers with one artist's
     * catalogue, and a batch of [BATCH_SIZE] straight off the front of that is five songs by
     * the same act back to back. Anything over [MAX_CONSECUTIVE_SAME_ARTIST] in a row is
     * pushed back down the buffer rather than dropped, so it still plays, just later.
     *
     * The run is counted from what is already in the queue, not just from this batch, or the
     * seam between two batches would still stack up.
     */
    private fun takeSpacedBatch(current: Session): List<Song> {
        val queued = ArrayList<Song>(BATCH_SIZE)
        var lastArtist = current.lastAppendedArtist
        var run = current.lastAppendedArtistRun
        val deferred = ArrayList<Song>()

        while (queued.size < BATCH_SIZE && current.pending.isNotEmpty()) {
            val song = current.pending.removeFirst()
            val artist = ArtistNameMatching.normalize(song.artist)
            val extendsRun = artist.isNotEmpty() && artist == lastArtist
            if (extendsRun && run >= MAX_CONSECUTIVE_SAME_ARTIST) {
                deferred.add(song)
                continue
            }
            run = if (extendsRun) run + 1 else 1
            lastArtist = artist
            queued.add(song)
        }

        // Spacing reorders, it never withholds. When the buffer holds nothing but the artist
        // being spaced out, deferring all of it would leave the round empty and the same songs
        // waiting again next time, which is a stalled station rather than a varied one.
        if (queued.isEmpty() && deferred.isNotEmpty()) {
            val forced = deferred.removeAt(0)
            lastArtist = ArtistNameMatching.normalize(forced.artist)
            run = current.lastAppendedArtistRun + 1
            queued.add(forced)
        }

        // Back to the front, in their original order: they were skipped for position, not
        // rejected, and the next round starts with a different artist in the way.
        deferred.asReversed().forEach(current.pending::addFirst)

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
         * Only used when a similar artist turns out to have no mix of their own. Kept at two so
         * a dead end still cannot fill a round with one act.
         */
        const val RELATED_ARTIST_TRACKS = 2

        /** Bounds one round's searches, so a run of empty artists cannot stall a top-up. */
        const val RELATED_ARTIST_ATTEMPTS_PER_ROUND = 3

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
