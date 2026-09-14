package com.example.music.data

import android.content.Context
import android.net.Uri
import com.example.music.util.PinyinUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Holds whatever song list the Library screen is currently showing, plus
 * favorite-toggle bookkeeping so the heart icon updates immediately instead
 * of waiting on a full re-fetch from the server.
 *
 * This is a process-wide singleton, so once loaded, the list survives
 * navigating away from and back to the Library/Home screens — callers should
 * check `library.value.isEmpty()` before calling [loadLibrary] again instead
 * of re-fetching on every screen entry (the server connection is often slow).
 * Across process restarts, [LibraryCache] fills the same gap on disk — see
 * [loadLibrary].
 *
 * The pinyin sort/index is also done exactly once here, on load, rather than
 * by each screen on every recomposition — screens should read [indexedLibrary]
 * (or [library], which mirrors the same sorted order) directly instead of
 * calling into [PinyinUtil] themselves.
 */
object SongRepository {
    private val _library = MutableStateFlow<List<Song>>(emptyList())
    val library: StateFlow<List<Song>> = _library.asStateFlow()

    private val _indexedLibrary = MutableStateFlow<List<PinyinUtil.IndexedSong>>(emptyList())
    val indexedLibrary: StateFlow<List<PinyinUtil.IndexedSong>> = _indexedLibrary.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun setIndexed(indexed: List<PinyinUtil.IndexedSong>) {
        _indexedLibrary.value = indexed
        _library.value = indexed.map { it.song }
    }

    /**
     * The key [SettingsRepository.moodLabels] is indexed by — title+artist,
     * not the file path or a Navidrome song id, so a song that's genuinely
     * replaced (retitled, or different content dropped in under the same
     * file) just falls back to "no label" instead of inheriting a stale one.
     */
    private fun moodKey(song: Song): String = "${song.title}${song.artist}"

    /**
     * Bakes [SettingsRepository.moodLabels] into each song's [Song.genre] —
     * this is the ONLY source of Energetic/Calm now, not the file's own genre
     * tag (which the app never reads for mood purposes; see
     * tools/export_mood_labels.py for how this map gets populated in bulk).
     * Applying it here means every downstream reader (Home's mood filter,
     * AudioCache's "keep energetic/calm cached" check, the now-playing mood
     * gradient) just reads [Song.genre] as normal and gets the labeled value
     * for free.
     */
    private fun applyMoodLabels(songs: List<Song>, labels: Map<String, String>): List<Song> =
        songs.map { song -> song.copy(genre = labels[moodKey(song)]) }

    /**
     * Reads [LibraryCache] (near-instant, on-disk) and shows it immediately
     * if nothing's loaded yet — a no-op once the real list (from cache or
     * network) is already in [library]. Split out from [loadLibrary] so
     * [MusicSource.ensureLoaded] can hydrate synchronously and then let the
     * actual network refresh run in the background, instead of every caller
     * (Home's mood tiles, the search overlay) blocking on a full sync — that
     * was the "have to wait for the whole library before you can even play
     * something" complaint.
     */
    suspend fun hydrateFromCache(context: Context, config: ServerConfig) {
        if (_library.value.isNotEmpty()) return
        val labels = SettingsRepository(context).moodLabels.first()
        LibraryCache.read(context, config)?.let { cached ->
            if (cached.isNotEmpty()) setIndexed(PinyinUtil.indexAndSort(applyMoodLabels(cached, labels)))
        }
    }

    /**
     * If this coroutine gets cancelled mid-load (e.g. the screen that called
     * it went away), the code after the call never runs — so _isLoading
     * must be reset in a finally, or it gets stuck true forever and
     * ensureLoaded() refuses to ever load again (for *either* source, since
     * they share this same flag) — this was the "Library just shows nothing,
     * network or USB, forever" bug.
     */
    suspend fun loadLibrary(context: Context, config: ServerConfig) {
        val labels = SettingsRepository(context).moodLabels.first()
        hydrateFromCache(context, config)
        _isLoading.value = true
        _error.value = null
        try {
            when (val result = SubsonicClient.getAllSongs(config)) {
                is SubsonicClient.ApiResult.Success -> {
                    setIndexed(PinyinUtil.indexAndSort(applyMoodLabels(result.data, labels)))
                    LibraryCache.write(context, config, result.data)
                }
                is SubsonicClient.ApiResult.Failure -> {
                    // Keep showing whatever the cache hydrated above — only
                    // surface the error if there was nothing to fall back on.
                    if (_library.value.isEmpty()) _error.value = result.message
                }
            }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Same idea as [loadLibrary] but reading off a granted USB drive tree
     * instead of the network — see UsbLibrarySource.
     *
     * Scanning a whole drive takes a few seconds, long enough that leaving
     * the screen mid-scan is common — that cancels this coroutine, and
     * CancellationException must be rethrown (not swallowed into _error)
     * or it surfaces as a bogus "加载失败：the coroutine scope left the
     * composition" the next time Library is opened.
     */
    suspend fun loadLocalLibrary(context: Context, treeUri: Uri, favoriteIds: Set<String>) {
        _isLoading.value = true
        _error.value = null
        try {
            var scanned = UsbLibrarySource.scanLibrary(context, treeUri)
            // Right after a drive is plugged in, the tree can still be empty
            // for a moment even though it's already granted and detected —
            // the DocumentsProvider backing it hasn't finished settling yet.
            // Retry a few times before accepting "empty" as real, instead of
            // leaving the user stuck on "库是空的" until they refresh by hand.
            var attempt = 0
            while (scanned.isEmpty() && attempt < 4) {
                delay(1000)
                scanned = UsbLibrarySource.scanLibrary(context, treeUri)
                attempt++
            }
            val labels = SettingsRepository(context).moodLabels.first()
            val songs = applyMoodLabels(scanned.map { it.copy(isFavorite = it.id in favoriteIds) }, labels)
            setIndexed(PinyinUtil.indexAndSort(songs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: "读取 U 盘失败"
        } finally {
            _isLoading.value = false
        }
    }

    /** Drops whatever's loaded — used when switching between network and USB sources so the next load starts clean. */
    fun clear() {
        _indexedLibrary.value = emptyList()
        _library.value = emptyList()
        _error.value = null
    }

    // NonCancellable for the same reason as setMoodLabel below: this is
    // launched from a screen-scoped coroutine scope (the heart icon in
    // Library/search), and navigating away right after tapping it must not
    // abort the write/star call mid-flight.
    suspend fun toggleFavorite(context: Context, config: ServerConfig, song: Song) = withContext(NonCancellable) {
        val wasFavorite = song.isFavorite

        if (song.localUri != null) {
            // No server to star it on — track it ourselves.
            SettingsRepository(context).toggleLocalFavorite(song.id)
            setIndexed(_indexedLibrary.value.map {
                if (it.song.id == song.id) it.copy(song = it.song.copy(isFavorite = !wasFavorite)) else it
            })
            return@withContext
        }

        // optimistic update so the heart responds instantly — order/letters are
        // unaffected by a favorite toggle, so this just patches the song in place
        setIndexed(_indexedLibrary.value.map {
            if (it.song.id == song.id) it.copy(song = it.song.copy(isFavorite = !wasFavorite)) else it
        })
        val result = if (wasFavorite) {
            SubsonicClient.unstar(config, song.id)
        } else {
            SubsonicClient.star(config, song.id)
        }
        if (result is SubsonicClient.ApiResult.Failure) {
            // roll back on failure
            setIndexed(_indexedLibrary.value.map {
                if (it.song.id == song.id) it.copy(song = it.song.copy(isFavorite = wasFavorite)) else it
            })
            _error.value = result.message
        }
    }

    /**
     * Sets (or, if [mood] is blank, clears) this song's Energetic/Calm label
     * — the one and only place that classification lives (see
     * [SettingsRepository.moodLabels]). Persists immediately so the phone's
     * copy of the label file is always current, and patches the in-memory
     * list so the change shows up right away without a reload.
     */
    suspend fun setMoodLabel(context: Context, song: Song, mood: String) {
        // NonCancellable: callers launch this from a screen-scoped
        // rememberCoroutineScope() (long-press a row in Library), and tapping
        // over to another tab right after marking a song disposes that
        // screen — which cancels the scope and, without this, could abort
        // the DataStore write mid-flight before it ever reaches disk. The
        // mark itself must always finish once started, regardless of what
        // the UI does a moment later.
        withContext(NonCancellable) {
            val settings = SettingsRepository(context)
            val key = moodKey(song)
            if (mood.isBlank()) settings.clearMoodLabel(key) else settings.setMoodLabel(key, mood)
            setIndexed(_indexedLibrary.value.map {
                if (it.song.id == song.id) it.copy(song = it.song.copy(genre = mood.ifBlank { null })) else it
            })
        }
    }

    /**
     * Re-applies whatever's currently saved in [SettingsRepository.moodLabels]
     * onto the already-loaded library, without a full network/USB reload —
     * for right after restoring an exported label file on a new phone (see
     * SettingsScreen), so the restored marks show up immediately instead of
     * only on the next full sync.
     */
    suspend fun reapplyMoodLabels(context: Context) {
        if (_indexedLibrary.value.isEmpty()) return
        val labels = SettingsRepository(context).moodLabels.first()
        setIndexed(_indexedLibrary.value.map { indexed ->
            indexed.copy(song = indexed.song.copy(genre = labels[moodKey(indexed.song)]))
        })
    }
}
