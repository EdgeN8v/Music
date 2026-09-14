package com.example.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.music.data.ServerConfig
import com.example.music.data.SettingsRepository
import com.example.music.data.Song
import com.example.music.data.SongRepository
import com.example.music.data.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Thin wrapper around a single ExoPlayer instance — in-memory queue, own
 * play/pause/skip API for the UI to call directly.
 *
 * The queue is deliberately NOT a real ExoPlayer playlist: songs are loaded
 * one at a time via setMediaItem so each one can go through the disk
 * cache/prefetch logic below. That means headset/Bluetooth/lock-screen skip
 * commands (routed in via [PlaybackService] + [QueueAwarePlayer]) have to be
 * explicitly wired to [skipToNext]/[skipToPrevious] rather than relying on
 * ExoPlayer's native next/previous handling, which would otherwise no-op.
 *
 * Streamed audio flows through [AudioCache] (a size-capped on-disk LRU
 * cache), and once a song starts playing, the next couple of songs in the
 * queue are quietly prefetched into that same cache in the background — the
 * point is that by the time playback reaches them, they're already local
 * instead of fighting the home-network connection in real time.
 */
enum class PlayMode { SEQUENTIAL, REPEAT_ONE, SHUFFLE }

object PlayerController {
    private var player: ExoPlayer? = null
    private var appContext: Context? = null
    private var cache: Cache? = null
    private var lastConfig: ServerConfig? = null
    private var prefetchJob: Job? = null
    private var positionTickerJob: Job? = null
    private val backgroundScope = CoroutineScope(Dispatchers.IO)
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate)
    private var settingsRepository: SettingsRepository? = null

    /** Guards [restoreFromSavedStateIfNeeded] to only ever act once per process — see its doc. */
    private var restoreAttempted = false

    // Backed by StateFlow (not a plain var) so the UI can show "this song is
    // 3rd in the queue" style badges instead of only knowing what's playing
    // right now.
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    /**
     * Song ids inserted via [playNext] that haven't played yet — lets the
     * queue view and Library's badges show "you explicitly queued this"
     * separately from the ambient continuation, and lets [playNext] stack
     * repeated taps in the order you tapped them (see its doc) instead of
     * each one jumping to the very front.
     */
    private val pendingNextIds = LinkedHashSet<String>()
    private val _explicitNextIds = MutableStateFlow<Set<String>>(emptySet())
    val explicitNextIds: StateFlow<Set<String>> = _explicitNextIds.asStateFlow()
    private fun syncExplicitNextIds() { _explicitNextIds.value = pendingNextIds.toSet() }

    private var queueValue: List<Song>
        get() = _queue.value
        set(value) { _queue.value = value }

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()
    private var currentIndexValue: Int
        get() = _currentIndex.value
        set(value) { _currentIndex.value = value }

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Whether the current queue was started from the 随机 (Random) tile, so the UI can show it. */
    private val _isShuffled = MutableStateFlow(false)
    val isShuffled: StateFlow<Boolean> = _isShuffled.asStateFlow()

    /**
     * How the queue advances once you reach the end — all three loop rather
     * than stopping dead, that's the whole point (previously: "listen through
     * all of 激情 once, then playback just stops" — not what anyone wants).
     * SEQUENTIAL keeps the queue's current order; SHUFFLE re-shuffles the
     * remaining songs every time it loops back to the start.
     */
    private val _playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    /**
     * Toggling this used to only affect the queue the *next* time it looped
     * back to the start or a fresh queue was built — the rest of what was
     * already queued up just sat there unchanged, so flipping sequential
     * <-> shuffle mid-playback visibly did nothing until you'd listened all
     * the way through. Now it re-sorts/re-shuffles the not-yet-played tail
     * of the current queue immediately (see [reorderRemainingQueue]).
     */
    fun cyclePlayMode() {
        val newMode = when (_playMode.value) {
            PlayMode.SEQUENTIAL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENTIAL
        }
        _playMode.value = newMode
        when (newMode) {
            PlayMode.SHUFFLE -> {
                reorderRemainingQueue(shuffle = true)
                _isShuffled.value = true
            }
            PlayMode.SEQUENTIAL -> {
                reorderRemainingQueue(shuffle = false)
                _isShuffled.value = false
            }
            PlayMode.REPEAT_ONE -> Unit // only affects what happens when the current song ends, not ordering
        }
    }

    /**
     * Re-sorts (or shuffles) everything still ahead of [currentIndexValue],
     * leaving already-played history and the current song untouched, and
     * leaving any explicit [playNext] adds pinned right after current in the
     * order you queued them (those are a deliberate choice, not part of the
     * ambient order this toggle controls). "Sequential" restores the same
     * order the library/mood filter would naturally produce, by looking up
     * each remaining song's position in [SongRepository.library] rather than
     * trying to remember whatever order it was in before an earlier shuffle.
     */
    private fun reorderRemainingQueue(shuffle: Boolean) {
        val current = queueValue
        if (currentIndexValue !in current.indices) return
        val head = current.subList(0, currentIndexValue + 1)
        val tail = current.subList(currentIndexValue + 1, current.size)
        if (tail.isEmpty()) return
        val explicitRun = tail.takeWhile { it.id in pendingNextIds }
        val ambientRest = tail.drop(explicitRun.size)
        if (ambientRest.isEmpty()) return
        val newAmbient = if (shuffle) {
            ambientRest.shuffled()
        } else {
            val naturalIndex = SongRepository.library.value.withIndex().associate { (i, s) -> s.id to i }
            ambientRest.sortedBy { naturalIndex[it.id] ?: Int.MAX_VALUE }
        }
        queueValue = head + explicitRun + newAmbient
    }

    /** Which Home mood tile (if any) started the current queue — "Passionate"/"Calm"/"Favorites"/"Random", or null if it came from Library/search. */
    private val _activeMood = MutableStateFlow<String?>(null)
    val activeMood: StateFlow<String?> = _activeMood.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /**
     * The stream URL embeds a fresh random salt+token on every call (see
     * [SubsonicClient.buildUrl]), so the same song gets a different URL
     * every time it's requested. CacheDataSource keys its cache entries by
     * request URI by default, which meant a song already fully cached from
     * a previous play was never recognized as cached the next time — the
     * "played it once, still had to reload it" bug. Keying on the stable
     * `id` query param instead of the whole URI fixes that.
     */
    private val cacheKeyFactory = CacheKeyFactory { dataSpec ->
        dataSpec.uri.getQueryParameter("id")?.let { "song:$it" } ?: dataSpec.uri.toString()
    }

    fun init(context: Context, cacheLimitMb: Int = 500, cacheEnabled: Boolean = true) {
        if (player != null) return
        val appContext = context.applicationContext
        this.appContext = appContext
        this.settingsRepository = SettingsRepository(appContext)
        val dataSourceFactory: DataSource.Factory = if (cacheEnabled) {
            cache = AudioCache.get(appContext, cacheLimitMb)
            cacheDataSourceFactory()
        } else {
            DefaultDataSource.Factory(appContext)
        }
        player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory))
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            // REPEAT_ONE only affects what happens when a track ends on
                            // its own — a manual "next" tap should still move forward,
                            // which is why this isn't handled inside skipToNext().
                            if (_playMode.value == PlayMode.REPEAT_ONE) {
                                playAt(currentIndexValue)
                            } else {
                                skipToNext()
                            }
                        }
                    }
                })
            }
        startPositionTicker()
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = mainScope.launch {
            var ticksSincePersist = 0
            while (true) {
                player?.let { exo ->
                    _currentPositionMs.value = exo.currentPosition.coerceAtLeast(0)
                    _durationMs.value = exo.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L
                }
                // Every ~10s while actually playing — covers "swiped the app
                // away mid-song" without writing to disk on every 500ms tick.
                // Pausing/skipping/starting a new queue save immediately on
                // their own (see persistPlaybackStateNow call sites), so this
                // is only the safety net for staying on one song a long time.
                if (_isPlaying.value) {
                    ticksSincePersist++
                    if (ticksSincePersist >= 20) {
                        ticksSincePersist = 0
                        persistPlaybackStateNow()
                    }
                } else {
                    ticksSincePersist = 0
                }
                delay(500)
            }
        }
    }

    /**
     * Snapshots the current queue/position to disk so [restoreFromSavedStateIfNeeded]
     * can pick it back up after the process dies — see call sites (pause,
     * skip, a fresh queue starting, and the periodic tick above).
     */
    private fun persistPlaybackStateNow() {
        val repo = settingsRepository ?: return
        _currentSong.value ?: return
        val queue = queueValue
        val index = currentIndexValue
        if (queue.isEmpty() || index !in queue.indices) return
        val state = SettingsRepository.PlaybackState(
            queueSongIds = queue.map { it.id },
            currentIndex = index,
            positionMs = _currentPositionMs.value,
            shuffled = _isShuffled.value,
            moodTag = _activeMood.value
        )
        backgroundScope.launch { repo.savePlaybackState(state) }
    }

    /** Seeks within the current song. No-op if nothing is loaded. */
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        persistPlaybackStateNow()
    }

    private fun cacheDataSourceFactory(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(requireNotNull(cache) { "AudioCache must be initialized before use" })
            .setCacheKeyFactory(cacheKeyFactory)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(requireNotNull(appContext)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun playAt(index: Int) {
        val exo = player ?: return
        if (index !in queueValue.indices) return
        currentIndexValue = index
        val song = queueValue[index]
        // This song just became "now playing", so it's no longer pending —
        // matters when it was itself an explicit playNext insertion.
        if (pendingNextIds.remove(song.id)) syncExplicitNextIds()

        val mediaUri = song.localUri ?: run {
            val config = lastConfig ?: return
            SubsonicClient.streamUrl(config, song.id)
        }
        exo.setMediaItem(MediaItem.fromUri(mediaUri))
        exo.prepare()
        exo.play()
        _currentSong.value = song
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
        persistPlaybackStateNow()

        // Local files are already local — nothing to prefetch.
        if (song.localUri == null) prefetchAhead() else prefetchJob?.cancel()
    }

    /**
     * Quietly downloads the next few queued songs into [AudioCache], not
     * just the very next one — on a slow/flaky connection, loading only one
     * song ahead meant you'd still hit a live-network stall a couple of
     * songs later. Runs sequentially (not in parallel) so it doesn't compete
     * with whatever's actively streaming right now.
     */
    private fun prefetchAhead(count: Int = 2) {
        prefetchJob?.cancel()
        val config = lastConfig ?: return
        val cacheRef = cache ?: return
        val targets = ((currentIndexValue + 1)..(currentIndexValue + count))
            .mapNotNull { queueValue.getOrNull(it) }
            .filter { it.localUri == null }
        if (targets.isEmpty()) return
        prefetchJob = backgroundScope.launch {
            for (song in targets) {
                yield() // cancellation checkpoint between songs — CacheWriter.cache() itself isn't cooperatively cancellable
                val url = SubsonicClient.streamUrl(config, song.id)
                val dataSource = CacheDataSource.Factory()
                    .setCache(cacheRef)
                    .setCacheKeyFactory(cacheKeyFactory)
                    .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
                    .createDataSource()
                try {
                    CacheWriter(dataSource, DataSpec(Uri.parse(url)), null, null).cache()
                } catch (e: Exception) {
                    // Best-effort only — playback will just stream it normally when it gets there.
                }
            }
        }
    }

    /**
     * Replaces the whole queue and starts playing from startIndex (default: the tapped song).
     * [shuffled] marks whether this queue came from the 随机 tile specifically — surfaced in
     * the mini player so it's clear when shuffle mode is active, separate from list ordering.
     */
    fun setQueueAndPlay(
        config: ServerConfig,
        songs: List<Song>,
        startIndex: Int = 0,
        shuffled: Boolean = false,
        moodTag: String? = null
    ) {
        lastConfig = config
        queueValue = songs
        _isShuffled.value = shuffled
        _activeMood.value = moodTag
        if (pendingNextIds.isNotEmpty()) {
            pendingNextIds.clear()
            syncExplicitNextIds()
        }
        playAt(startIndex)
    }

    /** Plays a single song right now, replacing whatever queue existed before. */
    fun playNow(config: ServerConfig, song: Song) {
        setQueueAndPlay(config, listOf(song), 0, shuffled = false)
    }

    /**
     * Inserts a song right after whatever else you've already explicitly
     * queued this way — tapping "play next" on A then B plays A, then B (the
     * order you tapped them), not B then A. It works by walking forward from
     * the current song past any songs already marked pending (previous
     * playNext calls not yet played) and inserting right after that run,
     * rather than always inserting at currentIndex+1.
     */
    fun playNext(config: ServerConfig, song: Song) {
        lastConfig = config
        if (queueValue.isEmpty()) {
            setQueueAndPlay(config, listOf(song), 0)
            return
        }
        val mutable = queueValue.toMutableList()
        var insertAt = currentIndexValue + 1
        while (insertAt < mutable.size && mutable[insertAt].id in pendingNextIds) insertAt++
        insertAt = insertAt.coerceIn(0, mutable.size)
        mutable.add(insertAt, song)
        queueValue = mutable
        pendingNextIds.add(song.id)
        syncExplicitNextIds()
    }

    /** Jumps straight to a specific position in the current queue — used by the queue view (tapping a row). */
    fun jumpToQueueIndex(index: Int) {
        playAt(index)
    }

    /**
     * Removes one upcoming song from the queue (queue view's per-row remove
     * button). Refuses to remove the currently playing song or anything
     * already played — only songs still ahead of [currentIndexValue].
     */
    fun removeFromQueue(index: Int) {
        if (index !in queueValue.indices || index <= currentIndexValue) return
        val removedId = queueValue[index].id
        val mutable = queueValue.toMutableList()
        mutable.removeAt(index)
        queueValue = mutable
        if (pendingNextIds.remove(removedId)) syncExplicitNextIds()
    }

    /**
     * Drag-to-reorder in the queue view. Both indices must be strictly ahead
     * of [currentIndexValue] — you can't drag the currently playing song or
     * anything already played.
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in queueValue.indices || toIndex !in queueValue.indices) return
        if (fromIndex <= currentIndexValue || toIndex <= currentIndexValue) return
        val mutable = queueValue.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        queueValue = mutable
    }

    /**
     * Resumes whatever was playing last session, paused and cued up at the
     * saved position instead of from 0:00 — call once, after the library has
     * loaded, before the user has started anything new this session (see the
     * [restoreAttempted]/queue-empty guards below; a real [setQueueAndPlay]
     * call — the user picking something themselves — should always win, so
     * this only ever acts once and only while nothing else has taken over).
     * Loads the media item and seeks but leaves it paused
     * (`playWhenReady = false`) so relaunching the app never starts blaring
     * audio on its own — it's just ready for one tap on play.
     */
    fun restoreFromSavedStateIfNeeded(config: ServerConfig, librarySongs: List<Song>) {
        if (restoreAttempted || queueValue.isNotEmpty()) return
        restoreAttempted = true
        val repo = settingsRepository ?: return
        val exo = player ?: return
        backgroundScope.launch {
            val state = repo.playbackState.first() ?: return@launch
            val byId = librarySongs.associateBy { it.id }
            val restoredQueue = state.queueSongIds.mapNotNull { byId[it] }
            if (restoredQueue.isEmpty()) return@launch
            val targetId = state.queueSongIds.getOrNull(state.currentIndex)
            val targetIndex = restoredQueue.indexOfFirst { it.id == targetId }
                .let { if (it >= 0) it else 0 }
            val song = restoredQueue[targetIndex]
            val mediaUri = song.localUri ?: SubsonicClient.streamUrl(config, song.id)
            mainScope.launch restoreOnMain@{
                if (queueValue.isNotEmpty()) return@restoreOnMain // something else started playing while we were reading disk
                lastConfig = config
                queueValue = restoredQueue
                currentIndexValue = targetIndex
                _isShuffled.value = state.shuffled
                _activeMood.value = state.moodTag
                _currentSong.value = song
                _currentPositionMs.value = state.positionMs
                _durationMs.value = 0L
                exo.setMediaItem(MediaItem.fromUri(mediaUri))
                exo.playWhenReady = false
                exo.prepare()
                exo.seekTo(state.positionMs)
            }
        }
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            persistPlaybackStateNow()
        } else {
            exo.play()
        }
    }

    /** Whether "next" does anything — true whenever the queue isn't empty, since it loops instead of stopping at the end. */
    fun hasNext(): Boolean = queueValue.isNotEmpty()

    fun skipToNext() {
        if (queueValue.isEmpty()) return
        val nextIndex = currentIndexValue + 1
        if (nextIndex < queueValue.size) {
            playAt(nextIndex)
        } else {
            // Looped back to the start instead of stopping — re-shuffle first if that's the mode.
            if (_playMode.value == PlayMode.SHUFFLE) queueValue = queueValue.shuffled()
            playAt(0)
        }
    }

    /** Goes to the previous track in the queue; restarts the current track if already at the start. */
    fun skipToPrevious() {
        if (currentIndexValue - 1 >= 0) {
            playAt(currentIndexValue - 1)
        } else {
            player?.seekTo(0)
            _currentPositionMs.value = 0L
            persistPlaybackStateNow()
        }
    }

    /** The raw ExoPlayer instance — only for wiring up the media-session/headset-button bridge (see PlaybackService). */
    fun getPlayer(): ExoPlayer? = player

    fun release() {
        prefetchJob?.cancel()
        positionTickerJob?.cancel()
        player?.release()
        player = null
    }
}
