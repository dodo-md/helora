package com.lostf1sh.pixelplayeross.data.youtube

import com.lostf1sh.pixelplayeross.data.model.Album
import com.lostf1sh.pixelplayeross.data.model.Artist
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import timber.log.Timber
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anonymous YouTube Music access via NewPipeExtractor.
 *
 * NewPipe is entirely blocking and its signature deobfuscation evaluates JavaScript through
 * Rhino, so every call here is confined to [ytDispatcher] and stream extraction is additionally
 * gated by a small semaphore — YouTube rate-limits concurrent extraction aggressively.
 *
 * There is no login: everything this class exposes is public YouTube data.
 */
@Singleton
class YouTubeMusicRepository @Inject constructor(
    private val downloader: NewPipeOkHttpDownloader
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ytDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    private val streamSemaphore = Semaphore(MAX_CONCURRENT_STREAM_EXTRACTIONS)

    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    /**
     * Extracting a stream also yields its related tracks, and radio needs both. Caching the
     * whole [StreamInfo] briefly means one extraction serves the stream URL and the radio seed.
     * Kept well under the ~6h lifetime of the signed URLs it holds.
     */
    private val streamInfoCache = object : LinkedHashMap<String, CachedStreamInfo>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedStreamInfo>) =
            size > STREAM_INFO_CACHE_SIZE
    }

    private data class CachedStreamInfo(val info: StreamInfo, val fetchedAtMs: Long) {
        fun isExpired(): Boolean = System.currentTimeMillis() - fetchedAtMs > STREAM_INFO_TTL_MS
    }

    /** Extractions currently running, keyed by video id, so callers can share one job. */
    private val inFlightExtractions = ConcurrentHashMap<String, Deferred<StreamInfo?>>()

    /** Outlives any single caller so shared and prefetched extractions survive cancellation. */
    private val repositoryScope = CoroutineScope(SupervisorJob() + ytDispatcher)

    /**
     * NewPipe needs a one-time global init. It is done lazily rather than in `Application.onCreate`
     * because the first extractor touch loads Rhino, and that is startup cost a user who never
     * opens YouTube search should not pay.
     */
    private suspend fun ensureInit() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(ytDispatcher) {
                // English metadata with the device's country: results stay regionally relevant,
                // but YouTube's generated titles ("Album - OK Computer") keep a known prefix we
                // can strip. Track and artist names are never translated, so nothing is lost.
                val country = Locale.getDefault().country.ifBlank { DEFAULT_COUNTRY }
                NewPipe.init(
                    downloader,
                    Localization(METADATA_LANGUAGE, country),
                    ContentCountry(country)
                )
                // Mix (radio) playlists are refused outright in consent-required regions
                // unless this is set: the extractor throws ContentNotAvailableException
                // with "Consent is required in some countries to view Mix playlists".
                YoutubeParsingHelper.setConsentAccepted(true)
            }
            initialized = true
        }
    }

    // ─── Search ────────────────────────────────────────────────────────

    /**
     * Runs the songs / albums / artists searches concurrently. Each is isolated so one
     * extractor failure degrades that section only instead of blanking the whole result.
     */
    suspend fun search(query: String): YouTubeSearchResult {
        if (query.isBlank()) return YouTubeSearchResult()
        ensureInit()

        return coroutineScope {
            val songs = async { runCatching { searchSongs(query) }.getOrElse { emptyList() } }
            val albums = async { runCatching { searchAlbums(query) }.getOrElse { emptyList() } }
            val artists = async { runCatching { searchArtists(query) }.getOrElse { emptyList() } }

            YouTubeSearchResult(
                songs = songs.await(),
                albums = albums.await(),
                artists = artists.await()
            )
        }
    }

    suspend fun searchSongs(query: String): List<Song> =
        searchItems(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toSong() }
            // YouTube lists the same recording once per album edition — searching "Numb"
            // returns it three times over Meteora, the anniversary edition and a reissue.
            // Results are ranked, so the first of each group is the one to keep.
            .distinctBy { trackKey(it.title, it.artist) }

    suspend fun searchAlbums(query: String): List<Album> =
        searchItems(query, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS)
            .filterIsInstance<PlaylistInfoItem>()
            .mapNotNull { it.toAlbum() }

    suspend fun searchArtists(query: String): List<Artist> =
        searchItems(query, YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS)
            .filterIsInstance<ChannelInfoItem>()
            .mapNotNull { it.toArtist() }

    private suspend fun searchItems(query: String, contentFilter: String): List<InfoItem> =
        withContext(ytDispatcher) {
            ensureInit()
            val service = ServiceList.YouTube
            // NOTE: MUSIC_ARTISTS is deliberately absent from getAvailableContentFilter(),
            // but fromQuery() does not validate against that list and both getUrl() and
            // YoutubeMusicSearchExtractor handle it. Verified against v0.26.5.
            val handler = service.searchQHFactory.fromQuery(query, listOf(contentFilter), "")
            SearchInfo.getInfo(service, handler).relatedItems.orEmpty()
        }

    // ─── Detail lookups ────────────────────────────────────────────────

    suspend fun getAlbum(browseId: String): YouTubeAlbumDetail = withContext(ytDispatcher) {
        ensureInit()
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, albumUrl(browseId))
        val albumTitle = stripReleaseTypePrefix(info.name.orEmpty())
        val tracks = info.relatedItems.orEmpty().filterIsInstance<StreamInfoItem>()
        // YouTube leaves the uploader blank on album playlists more often than not; the
        // tracks themselves always carry the artist.
        val albumArtist = info.uploaderName?.takeIf { it.isNotBlank() }
            ?: tracks.firstNotNullOfOrNull { it.uploaderName?.takeIf { name -> name.isNotBlank() } }
                .orEmpty()

        val songs = tracks
            .mapNotNull { item ->
                item.toSong(
                    albumTitle = albumTitle.ifBlank { DEFAULT_ALBUM_NAME },
                    albumId = YouTubeIds.albumId(browseId),
                    fallbackArtist = albumArtist
                )
            }
            .mapIndexed { index, song -> song.copy(trackNumber = index + 1) }

        YouTubeAlbumDetail(
            album = Album(
                id = YouTubeIds.albumId(browseId),
                title = albumTitle,
                artist = albumArtist,
                year = 0,
                dateAdded = 0L,
                albumArtUriString = info.thumbnails.bestUrl(),
                songCount = songs.size,
                albumArtist = albumArtist.takeIf { it.isNotBlank() },
                ytmBrowseId = browseId
            ),
            songs = songs
        )
    }

    /**
     * YouTube Music artist search returns auto-generated "Topic" channels, which expose **no**
     * tabs at all. Their catalogue lives in the channel's uploads playlist instead (the channel
     * id with `UC` swapped for `UU`), so both shapes are handled: tabs when present, uploads
     * playlist otherwise.
     */
    suspend fun getArtist(channelId: String): YouTubeArtistDetail = withContext(ytDispatcher) {
        ensureInit()
        val service = ServiceList.YouTube
        val info = runCatching { ChannelInfo.getInfo(service, artistUrl(channelId)) }.getOrNull()
        val artistName = info?.name?.let(::stripTopicSuffix).orEmpty()

        // A failing tab degrades that section to empty rather than failing the whole lookup.
        val tabItems = info?.tabs.orEmpty().associateWith { tab ->
            runCatching { ChannelTabInfo.getInfo(service, tab).relatedItems.orEmpty() }
                .getOrElse { emptyList() }
        }

        val tabSongs = tabItems
            .filterKeys { tab -> tab.contentFilters.any { it in TRACK_TABS } }
            .values.flatten()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toSong(fallbackArtist = artistName) }

        val songs = tabSongs.ifEmpty { uploadsPlaylistSongs(channelId, artistName) }

        val albums = tabItems
            .filterKeys { it.contentFilters.contains(ChannelTabs.ALBUMS) }
            .values.flatten()
            .filterIsInstance<PlaylistInfoItem>()
            .mapNotNull { it.toAlbum() }

        YouTubeArtistDetail(
            artist = Artist(
                id = YouTubeIds.artistId(channelId),
                name = artistName,
                songCount = songs.size,
                imageUrl = info?.avatars.bestUrl(),
                ytmChannelId = channelId
            ),
            songs = songs,
            albums = albums
        )
    }

    private fun uploadsPlaylistSongs(channelId: String, artistName: String): List<Song> {
        if (!channelId.startsWith(CHANNEL_ID_PREFIX)) return emptyList()
        val uploadsId = UPLOADS_ID_PREFIX + channelId.removePrefix(CHANNEL_ID_PREFIX)
        return runCatching {
            PlaylistInfo.getInfo(ServiceList.YouTube, uploadsUrl(uploadsId))
                .relatedItems.orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toSong(fallbackArtist = artistName) }
        }.getOrElse {
            Timber.w(it, "YouTubeMusicRepository: uploads playlist failed for %s", channelId)
            emptyList()
        }
    }

    // ─── Playback ──────────────────────────────────────────────────────

    /**
     * Resolves the best progressive audio stream for [videoId].
     *
     * Only `PROGRESSIVE_HTTP` streams are eligible: the proxy that serves this URL pipes raw
     * bytes with Range passthrough, so a DASH or HLS manifest would reach ExoPlayer disguised
     * as an audio byte stream and fail.
     */
    suspend fun getAudioStreamUrl(videoId: String): String? {
        val info = streamInfo(videoId) ?: return null
        val audioStreams = info.audioStreams.orEmpty()
        val selected = audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.isUrl && !it.content.isNullOrBlank() }
            .filter { it.format?.mimeType?.startsWith("audio/") == true }
            .maxByOrNull { it.averageBitrate }

        if (selected == null) {
            Timber.tag("YouTubeMusicRepository").w(
                "[YT_DEBUG] no playable progressive audio stream; id=%s audioStreams=%d",
                videoId,
                audioStreams.size
            )
            return null
        }

        val host = runCatching { URI(selected.content.orEmpty()).host }.getOrNull()
        Timber.tag("YouTubeMusicRepository").d(
            "[YT_DEBUG] selected audio stream; id=%s host=%s mime=%s bitrate=%d",
            videoId,
            host,
            selected.format?.mimeType,
            selected.averageBitrate
        )
        return selected.content
    }

    /**
     * Picks the stream to save to disk, which is a different tradeoff from streaming.
     *
     * Playback takes the highest bitrate, usually Opus in a WebM container. A downloaded file
     * goes into the shared Music folder where other players and car head units have to open it,
     * and WebM/Opus support there is patchy — so M4A wins even at a slightly lower bitrate.
     */
    suspend fun getDownloadableStream(videoId: String): DownloadableStream? {
        val info = streamInfo(videoId) ?: return null
        val candidates = info.audioStreams.orEmpty()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.isUrl && !it.content.isNullOrBlank() }
            .filter { it.format?.mimeType?.startsWith("audio/") == true }
        if (candidates.isEmpty()) return null

        val preferred = candidates
            .filter { it.format == MediaFormat.M4A }
            .maxByOrNull { it.averageBitrate }
            ?: candidates.maxByOrNull { it.averageBitrate }
            ?: return null

        val format = preferred.format
        return DownloadableStream(
            url = preferred.content.orEmpty(),
            mimeType = format?.mimeType ?: "audio/mp4",
            extension = format?.suffix ?: "m4a",
            averageBitrate = preferred.averageBitrate
        )
    }

    /** MIME type of the stream [getAudioStreamUrl] would pick, for the proxy's content type. */
    suspend fun getAudioMimeType(videoId: String): String? =
        streamInfo(videoId)?.audioStreams.orEmpty()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.isUrl && !it.content.isNullOrBlank() }
            .filter { it.format?.mimeType?.startsWith("audio/") == true }
            .maxByOrNull(AudioStream::getAverageBitrate)
            ?.format?.mimeType

    /**
     * Opens the YouTube Music radio station seeded on [videoId].
     *
     * This deliberately does **not** use `StreamInfo.relatedItems`. That is YouTube's generic
     * "related videos" sidebar, which mixes in other uploads of the same song and unrelated
     * viral tracks — the opposite of "more like this". A mix playlist (`RDAMVM<videoId>`) is
     * the actual station YouTube Music builds for a song.
     */
    suspend fun getRadioStation(videoId: String): RadioPage? = withContext(ytDispatcher) {
        ensureInit()
        // RDAMVM is the YouTube *Music* station and only exists for tracks YT Music knows.
        // RD is the generic stream mix, which covers everything else.
        for (stationUrl in listOf(musicStationUrl(videoId), genericStationUrl(videoId))) {
            val page = runCatching { fetchStationPage(stationUrl) }
                .onFailure { Timber.w(it, "Radio station failed: %s", stationUrl) }
                .getOrNull()
            if (page != null && page.songs.isNotEmpty()) return@withContext page
        }
        null
    }

    /** Pulls the next slice of an open station. */
    suspend fun getRadioNextPage(stationUrl: String, page: Page): RadioPage? =
        withContext(ytDispatcher) {
            ensureInit()
            runCatching {
                val next = PlaylistInfo.getMoreItems(ServiceList.YouTube, stationUrl, page)
                RadioPage(
                    songs = next.items.orEmpty().mapNotNull { it.toSong() },
                    nextPage = next.nextPage,
                    stationUrl = stationUrl
                )
            }.onFailure {
                Timber.w(it, "Radio station page failed: %s", stationUrl)
            }.getOrNull()
        }

    private fun fetchStationPage(stationUrl: String): RadioPage {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, stationUrl)
        return RadioPage(
            songs = info.relatedItems.orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toSong() },
            nextPage = info.nextPage,
            stationUrl = stationUrl
        )
    }

    /**
     * Last resort when a track has no mix at all: other songs by the same artist. Still closer
     * to "more like this" than falling back to raw related videos would be.
     */
    suspend fun getArtistFallbackSongs(seed: Song): List<Song> = withContext(ytDispatcher) {
        ensureInit()
        val artistName = seed.artist.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        runCatching { searchSongs(artistName) }
            .getOrElse { emptyList() }
            .filter { it.ytVideoId != null && it.ytVideoId != seed.ytVideoId }
    }

    /**
     * Finds the official distributed release of a track.
     *
     * Mixes hand back whatever upload YouTube feels like, often the music video. Those are cut
     * differently from the release that goes to streaming services — extra intros, held endings
     * — so synced lyrics, which are timed against the distributed version, drift out.
     *
     * A `MUSIC_SONGS` search only ever returns catalogue entries ("- Topic" art tracks), so
     * looking the track up again is enough to pin the station to the version lyrics expect.
     * Returns null unless both title and artist match, so a bad search cannot silently swap in
     * a different song.
     */
    suspend fun resolveArtTrack(song: Song): Song? {
        val title = song.title.takeIf { it.isNotBlank() } ?: return null
        val artist = song.artist.takeIf { it.isNotBlank() } ?: return null

        val expectedTitle = normalizeForMatching(title)
        val expectedArtist = normalizeForMatching(artist)
        if (expectedTitle.isBlank() || expectedArtist.isBlank()) return null

        val candidates = runCatching { searchSongs("$title $artist") }.getOrElse { return null }
        return candidates.firstOrNull { candidate ->
            // A radio edit and its album version differ by a minute or so, and correcting that
            // is the point. A ten-minute live cut or extended remix under the same title is a
            // different recording, and swapping it in would change what the user is hearing.
            val durationDelta = kotlin.math.abs(candidate.duration - song.duration)
            if (song.duration > 0 && durationDelta > MAX_ART_TRACK_DRIFT_MS) return@firstOrNull false
            val candidateTitle = normalizeForMatching(candidate.title)
            val candidateArtist = normalizeForMatching(candidate.artist)
            val titleMatches = candidateTitle == expectedTitle ||
                candidateTitle.contains(expectedTitle) ||
                expectedTitle.contains(candidateTitle)
            val artistMatches = candidateArtist.contains(expectedArtist) ||
                expectedArtist.contains(candidateArtist)
            titleMatches && artistMatches
        }
    }

    /**
     * Finds the YouTube track matching a local file, for starting a station from the user's own
     * library.
     *
     * Deliberately strict: the artist must match and the duration must be within a few seconds.
     * Song titles collide constantly across covers, remixes and live versions, and seeding a
     * station from the wrong one produces a station that feels random — worse than declining.
     */
    suspend fun findMatchingVideoId(title: String, artist: String, durationMs: Long): String? {
        if (title.isBlank() || artist.isBlank() || durationMs <= 0L) return null
        val candidates = runCatching { searchSongs("$title $artist") }.getOrElse { emptyList() }
        return candidates.firstOrNull { candidate ->
            candidate.matches(title, artist, durationMs)
        }?.ytVideoId
    }

    private fun Song.matches(title: String, artist: String, durationMs: Long): Boolean {
        if (ytVideoId == null) return false
        val expectedArtist = normalizeForMatching(artist)
        val actualArtist = normalizeForMatching(this.artist)
        if (expectedArtist.isBlank() || actualArtist.isBlank()) return false

        // Either direction: local tags say "Radiohead", YouTube may say "Radiohead - Topic",
        // and compilations often prefix the uploader with a label name.
        val artistMatches = actualArtist.contains(expectedArtist) ||
            expectedArtist.contains(actualArtist)

        // The title check is what stops "Creep" by "Taylor Swift" from matching an unrelated
        // Taylor Swift track of the same length. Containment both ways covers YouTube titles
        // carrying extra decoration ("Creep (Official Video)") and "Artist - Title" formatting.
        val expectedTitle = normalizeForMatching(title)
        val actualTitle = normalizeForMatching(this.title)
        val titleMatches = expectedTitle.isNotBlank() && actualTitle.isNotBlank() &&
            (actualTitle.contains(expectedTitle) || expectedTitle.contains(actualTitle))

        val durationMatches = kotlin.math.abs(duration - durationMs) <= DURATION_TOLERANCE_MS
        return artistMatches && titleMatches && durationMatches
    }

    /**
     * Starts extraction for [videoId] without waiting for it, so a track the user is likely to
     * play next is already resolved by the time they tap it. Safe to call repeatedly: a cached
     * or already-running extraction is a no-op.
     */
    fun prefetchStream(videoId: String) {
        if (cachedStreamInfo(videoId) != null || inFlightExtractions.containsKey(videoId)) return
        repositoryScope.launch { streamInfo(videoId) }
    }

    private fun cachedStreamInfo(videoId: String): StreamInfo? = synchronized(streamInfoCache) {
        val cached = streamInfoCache[videoId] ?: return null
        if (cached.isExpired()) {
            streamInfoCache.remove(videoId)
            return null
        }
        cached.info
    }

    private suspend fun streamInfo(videoId: String): StreamInfo? {
        ensureInit()
        cachedStreamInfo(videoId)?.let { return it }

        // Extraction costs seconds, and two things routinely want the same video at the same
        // moment: resolving the stream URL to start playback, and reading its related tracks to
        // seed radio. Without this they would each run a full extraction and hold both semaphore
        // permits, delaying the next track's prefetch as well. Sharing one job fixes both.
        val extraction = inFlightExtractions.computeIfAbsent(videoId) {
            repositoryScope.async {
                try {
                    streamSemaphore.withPermit {
                        cachedStreamInfo(videoId) ?: runCatching {
                            StreamInfo.getInfo(ServiceList.YouTube, watchUrl(videoId)).also { info ->
                                synchronized(streamInfoCache) {
                                    streamInfoCache[videoId] =
                                        CachedStreamInfo(info, System.currentTimeMillis())
                                }
                            }
                        }.onFailure {
                            Timber.w(it, "YouTubeMusicRepository: extraction failed for %s", videoId)
                        }.getOrNull()
                    }
                } finally {
                    inFlightExtractions.remove(videoId)
                }
            }
        }

        // Runs in the repository's own scope, so a caller giving up (the user skipping the track)
        // lets the extraction finish and populate the cache instead of throwing the work away.
        return try {
            extraction.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "YouTubeMusicRepository: awaiting extraction failed for %s", videoId)
            null
        }
    }

    // ─── Mapping ───────────────────────────────────────────────────────

    private fun StreamInfoItem.toSong(
        albumTitle: String = DEFAULT_ALBUM_NAME,
        albumId: Long = DEFAULT_ALBUM_ID,
        fallbackArtist: String = ""
    ): Song? {
        val videoId = videoIdOrNull(url) ?: return null
        val artistName = uploaderName?.takeIf { it.isNotBlank() }
            ?: fallbackArtist.takeIf { it.isNotBlank() }
            ?: return null
        val channelId = channelIdOrNull(uploaderUrl)
        val artistId = channelId?.let(YouTubeIds::artistId) ?: YouTubeIds.artistId(artistName)

        return Song(
            id = YouTubeIds.songId(videoId).toString(),
            title = cleanTrackTitle(name.orEmpty(), artistName).ifBlank { return null },
            artist = artistName,
            artistId = artistId,
            artists = listOf(ArtistRef(id = artistId, name = artistName, isPrimary = true)),
            album = albumTitle,
            albumId = albumId,
            albumArtist = artistName,
            path = "",
            contentUriString = "$URI_SCHEME://$videoId",
            albumArtUriString = thumbnails.bestUrl(),
            // NewPipe reports seconds; the rest of the app works in milliseconds.
            duration = duration.coerceAtLeast(0L) * 1000L,
            genre = DEFAULT_GENRE,
            mimeType = null,
            bitrate = null,
            sampleRate = null,
            ytVideoId = videoId
        )
    }

    private fun PlaylistInfoItem.toAlbum(): Album? {
        val browseId = playlistIdOrNull(url) ?: return null
        return Album(
            id = YouTubeIds.albumId(browseId),
            title = stripReleaseTypePrefix(name.orEmpty()).ifBlank { return null },
            artist = uploaderName.orEmpty(),
            year = 0,
            dateAdded = 0L,
            albumArtUriString = thumbnails.bestUrl(),
            songCount = streamCount.coerceAtLeast(0L).toInt(),
            albumArtist = uploaderName?.takeIf { it.isNotBlank() },
            ytmBrowseId = browseId
        )
    }

    private fun ChannelInfoItem.toArtist(): Artist? {
        val channelId = channelIdOrNull(url) ?: return null
        return Artist(
            id = YouTubeIds.artistId(channelId),
            name = stripTopicSuffix(name.orEmpty()).ifBlank { return null },
            songCount = 0,
            imageUrl = thumbnails.bestUrl(),
            ytmChannelId = channelId
        )
    }

    /** Highest-resolution variant available; NewPipe returns these unordered. */
    private fun List<Image>?.bestUrl(): String? =
        this?.filter { it.url.isNotBlank() }
            ?.maxByOrNull { it.height }
            ?.url

    companion object {
        const val URI_SCHEME = "ytmusic"
        const val DEFAULT_GENRE = "YouTube Music"

        /**
         * Loose tracks (search results, radio) have no album of their own. Grouping them under
         * one pseudo-album keeps the library tidy after they are saved, instead of creating a
         * junk single-track album per song.
         */
        const val DEFAULT_ALBUM_NAME = "YouTube Music"
        val DEFAULT_ALBUM_ID = YouTubeIds.albumId("__youtube_music_singles__")

        private const val DEFAULT_COUNTRY = "US"
        private const val METADATA_LANGUAGE = "en"
        private const val CHANNEL_ID_PREFIX = "UC"
        private const val UPLOADS_ID_PREFIX = "UU"
        private const val MAX_CONCURRENT_STREAM_EXTRACTIONS = 2
        private const val STREAM_INFO_CACHE_SIZE = 32
        private const val STREAM_INFO_TTL_MS = 5L * 60 * 1000

        private val TRACK_TABS = setOf(ChannelTabs.TRACKS, ChannelTabs.VIDEOS)

        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

        // The separator YouTube uses is an en dash; a hyphen is accepted defensively.
        private val RELEASE_TYPE_PREFIX_REGEX =
            Regex("^(Album|Single|EP)\\s*[\u2013-]\\s*", RegexOption.IGNORE_CASE)

        private const val TOPIC_SUFFIX = " - Topic"

        /** Widest gap still treated as the same recording in a different cut. */
        private const val MAX_ART_TRACK_DRIFT_MS = 90_000L
        private const val MUSIC_MIX_PREFIX = "RDAMVM"
        private const val MIX_PREFIX = "RD"

        /** Same track, different master/upload: a few seconds of drift is normal. */
        const val DURATION_TOLERANCE_MS = 5_000L

        private val TOPIC_SUFFIX_REGEX = Regex("\\s*-\\s*topic\\s*$")
        private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val TITLE_SEPARATORS = charArrayOf('-', '\u2013', ':', '\u2014')

        // Bracketed segments that are purely upload decoration.
        private val NOISE_BRACKET_REGEX = Regex(
            "[\\[(][^\\[\\]()]*\\b(official|lyrics?|visuali[sz]er|music\\s*video|audio|" +
                "remaster(ed)?|hd|hq|4k|8k|mv|full\\s*video|video\\s*oficial)\\b" +
                "[^\\[\\]()]*[\\])]",
            RegexOption.IGNORE_CASE
        )

        fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

        fun albumUrl(browseId: String): String =
            "https://music.youtube.com/playlist?list=$browseId"

        fun artistUrl(channelId: String): String =
            "https://music.youtube.com/channel/$channelId"

        /** YouTube Music song station. */
        fun musicStationUrl(videoId: String): String =
            "https://www.youtube.com/watch?v=$videoId&list=$MUSIC_MIX_PREFIX$videoId"

        /** Generic stream mix, for videos YouTube Music has no station for. */
        fun genericStationUrl(videoId: String): String =
            "https://www.youtube.com/watch?v=$videoId&list=$MIX_PREFIX$videoId"

        /**
         * Strips YouTube upload decoration from a track title.
         *
         * Mixes pull in ordinary YouTube uploads, so titles arrive as
         * "Coldplay - Yellow (Official Video)" rather than "Yellow". Only known noise markers
         * are removed, so meaningful parentheses like "(Acoustic)" or "(feat. X)" survive.
         */
        fun cleanTrackTitle(rawTitle: String, artist: String): String {
            var title = NOISE_BRACKET_REGEX.replace(rawTitle, " ")
            val normalizedArtist = normalizeForMatching(artist)
            if (normalizedArtist.isNotBlank()) {
                // "Coldplay - Yellow" -> "Yellow", but only when the prefix really is the artist.
                val separatorIndex = title.indexOfFirst { it in TITLE_SEPARATORS }
                if (separatorIndex > 0) {
                    val prefix = normalizeForMatching(title.substring(0, separatorIndex))
                    // Compared without spaces so a channel name like "TheCranberriesTV" still
                    // recognises the "The Cranberries - " prefix as its own.
                    val squashedPrefix = prefix.replace(" ", "")
                    val squashedArtist = normalizedArtist.replace(" ", "")
                    val prefixIsArtist = squashedPrefix.isNotBlank() && (
                        squashedArtist.contains(squashedPrefix) ||
                            squashedPrefix.contains(squashedArtist)
                        )
                    if (prefixIsArtist) {
                        title = title.substring(separatorIndex + 1)
                    }
                }
            }
            return title.replace(WHITESPACE_REGEX, " ").trim().ifBlank { rawTitle.trim() }
        }

        /**
         * Identity of a *recording* rather than of an upload.
         *
         * Mixes routinely contain the same song twice under different video ids (a topic
         * upload and an official channel upload). Deduplicating on video id alone lets both
         * through, so a station repeats itself.
         */
        fun trackKey(title: String, artist: String): String =
            normalizeForMatching(title).replace(" ", "") + "|" +
                normalizeForMatching(artist).replace(" ", "")

        /** Casing, punctuation and decorations differ constantly between tags and YouTube. */
        fun normalizeForMatching(value: String): String =
            value.lowercase()
                .replace(TOPIC_SUFFIX_REGEX, "")
                .replace(NON_ALPHANUMERIC_REGEX, " ")
                .trim()
                .replace(WHITESPACE_REGEX, " ")

        fun uploadsUrl(uploadsId: String): String =
            "https://www.youtube.com/playlist?list=$uploadsId"

        /**
         * YouTube titles album playlists as "Album - OK Computer" / "Single - Creep".
         * Deterministic because [METADATA_LANGUAGE] pins the metadata language to English.
         */
        fun stripReleaseTypePrefix(name: String): String =
            RELEASE_TYPE_PREFIX_REGEX.replace(name, "").trim()

        /** Auto-generated artist channels are named "Radiohead - Topic". */
        fun stripTopicSuffix(name: String): String =
            name.removeSuffix(TOPIC_SUFFIX).trim()

        /** Extracts the `v=` parameter (or short-link path) from any YouTube watch URL. */
        fun videoIdOrNull(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val candidate = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            return candidate?.takeIf { VIDEO_ID_REGEX.matches(it) }
        }

        fun playlistIdOrNull(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
        }

        fun channelIdOrNull(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return Regex("/channel/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
        }
    }
}
