package com.example.music.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Decides, at any given moment, whether the app should be reading from the
 * network (Navidrome) or a plugged-in USB drive, and drives [SongRepository]
 * accordingly. Screens call [ensureLoaded] instead of SongRepository.loadLibrary
 * directly, and read [isUsbActive] when they need to know which source is
 * live (mood-tile filtering and favorites work differently for local files).
 *
 * USB presence is polled rather than driven by ACTION_MEDIA_MOUNTED — that
 * broadcast is unreliable across vendors/API levels for a non-system app,
 * while polling StorageManager every couple seconds is cheap and works
 * everywhere, including whatever Android build a given car head unit runs.
 */
object MusicSource {
    private lateinit var appContext: Context
    private lateinit var settingsRepository: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val _usbPresent = MutableStateFlow(false)
    val usbPresent: StateFlow<Boolean> = _usbPresent.asStateFlow()

    private val _mode = MutableStateFlow(MusicMode.AUTO)
    val mode: StateFlow<MusicMode> = _mode.asStateFlow()

    private val _usbTreeUri = MutableStateFlow<String?>(null)
    val usbTreeUri: StateFlow<String?> = _usbTreeUri.asStateFlow()

    private val _isUsbActive = MutableStateFlow(false)
    val isUsbActive: StateFlow<Boolean> = _isUsbActive.asStateFlow()

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        settingsRepository = SettingsRepository(appContext)

        scope.launch {
            while (true) {
                _usbPresent.value = UsbLibrarySource.isUsbPresent(appContext)
                delay(2000)
            }
        }
        scope.launch { settingsRepository.musicMode.collect { _mode.value = it } }
        scope.launch { settingsRepository.usbTreeUri.collect { _usbTreeUri.value = it } }
        scope.launch {
            combine(_mode, _usbPresent, _usbTreeUri) { mode, present, treeUri ->
                val granted = treeUri != null
                when (mode) {
                    MusicMode.USB -> granted
                    MusicMode.NETWORK -> false
                    MusicMode.AUTO -> present && granted
                }
            }.collect { active ->
                if (active != _isUsbActive.value) {
                    _isUsbActive.value = active
                    // Source changed under us — drop the old list so ensureLoaded reloads from the right place.
                    SongRepository.clear()
                    if (active) {
                        // Don't wait for some screen's LaunchedEffect to notice isUsbActive
                        // flipped and call ensureLoaded — that race is exactly what caused
                        // "库是空的" right after plugging in USB: the screen's effect could
                        // already have fired (and no-opped) before this flow settled, and
                        // nothing else would ever retry until a manual refresh. Load right
                        // here instead, the moment USB is actually ready.
                        val treeUri = _usbTreeUri.value
                        if (treeUri != null) {
                            scope.launch {
                                val favorites = settingsRepository.localFavoriteIds.first()
                                SongRepository.loadLocalLibrary(appContext, Uri.parse(treeUri), favorites)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Loads from whichever source is active right now, unless something's
     * already loaded (or loading). Safe to call on every screen entry.
     *
     * For network mode, hydrates the on-disk cache synchronously first (fast)
     * so a caller — Home's mood tiles, the search overlay — has something to
     * filter/search over right away, then lets the real network refresh run
     * in the background instead of making that caller wait on it. If there
     * was nothing to hydrate (first-ever launch, or USB), there's nothing to
     * show yet either way, so this just falls through to the normal full,
     * awaited load.
     */
    suspend fun ensureLoaded(config: ServerConfig) {
        if (SongRepository.library.value.isNotEmpty() || SongRepository.isLoading.value) return
        if (!_isUsbActive.value && config.isConfigured) {
            SongRepository.hydrateFromCache(appContext, config)
        }
        if (SongRepository.library.value.isNotEmpty()) {
            scope.launch { loadFromActiveSource(config) }
        } else {
            loadFromActiveSource(config)
        }
    }

    /** Reloads from whichever source is active right now, regardless of what's already loaded — for the manual refresh button. */
    suspend fun forceReload(config: ServerConfig) {
        loadFromActiveSource(config)
    }

    private suspend fun loadFromActiveSource(config: ServerConfig) {
        if (_isUsbActive.value) {
            val treeUri = _usbTreeUri.value ?: return
            val favorites = settingsRepository.localFavoriteIds.first()
            SongRepository.loadLocalLibrary(appContext, Uri.parse(treeUri), favorites)
        } else if (config.isConfigured) {
            SongRepository.loadLibrary(appContext, config)
        }
    }

    /** Called after the user grants folder access via the system picker (see UsbLibrarySource.createAccessIntent). */
    fun onUsbAccessGranted(uri: Uri) {
        scope.launch { settingsRepository.saveUsbTreeUri(uri.toString()) }
    }
}
