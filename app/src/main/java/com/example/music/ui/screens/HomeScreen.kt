package com.example.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.music.data.MusicSource
import com.example.music.data.ServerConfig
import com.example.music.data.SettingsRepository
import com.example.music.data.SongRepository
import com.example.music.playback.PlayMode
import com.example.music.playback.PlayerController
import com.example.music.ui.components.MoodTile
import com.example.music.ui.components.QueueSheet
import com.example.music.ui.components.SongSearchOverlay
import com.example.music.ui.theme.MoodColors
import kotlinx.coroutines.launch

/**
 * Home: logo + app name on the left, search on the right, and four full-width
 * mood tiles stacked to fill the rest of the screen — the "open the app,
 * tap the mood you're in" flow.
 *
 * 激情/平静 come entirely from [SettingsRepository.moodLabels] — a phone-side
 * label file, not the song's own genre tag. It's populated in bulk from an
 * offline PC classification step (librosa/essentia script → tools/
 * export_mood_labels.py → imported via Settings) and can be set or cleared
 * per-song from the phone via [SongRepository.setMoodLabel] (Library screen,
 * long-press a song) without touching the file at all. If a tile comes back
 * empty it means no songs currently resolve to that mood, not a bug.
 *
 * Tapping search pops up an in-place search box with the keyboard open
 * (see [SongSearchOverlay]) instead of jumping over to the Library tab —
 * picking a result plays it immediately. What's currently playing (and
 * whether 随机/shuffle mode is on) shows up in the persistent mini player
 * at the bottom of the screen, wired up in AppNavigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val config by settingsRepository.config.collectAsState(initial = ServerConfig())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSearch by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val librarySongs by SongRepository.library.collectAsState()
    val activeMood by PlayerController.activeMood.collectAsState()
    val isUsbActive by MusicSource.isUsbActive.collectAsState()
    // Already sorted once by SongRepository when it loaded — no need to
    // re-sort (and re-transliterate every title) on every Home visit.
    val sortedLibrary = librarySongs

    // Kick this off as soon as Home appears, not just when search opens —
    // ensureLoaded hydrates from the on-disk cache synchronously (near
    // instant) and backgrounds the real refresh, so by the time someone
    // actually taps a mood tile or opens search, the list is normally
    // already there instead of the tap itself having to wait on it.
    LaunchedEffect(config, isUsbActive) {
        MusicSource.ensureLoaded(config)
    }

    /**
     * All four tiles filter/shuffle whatever [SongRepository] already has
     * loaded — network or USB, genre is the same phone-side label either way
     * (see [SongRepository.setMoodLabel]), so 激情/平静 work identically
     * regardless of source; 收藏 uses the favorite set, 随机 just shuffles
     * everything. This used to hit the server fresh per tap on network, but
     * that meant a label change never took effect there — reading the
     * already-loaded (and by now near-instantly cached) list instead fixes
     * that and drops a redundant network round-trip.
     */
    fun playMood(mood: String) {
        scope.launch {
            if (!isUsbActive && !config.isConfigured) {
                snackbarHostState.showSnackbar("先去 Settings 里配置 Navidrome 服务器，或者插入 U 盘")
                return@launch
            }
            MusicSource.ensureLoaded(config)
            val all = SongRepository.library.value
            val filtered = when (mood) {
                "Energetic", "Calm" -> all.filter { it.genre.equals(mood, ignoreCase = true) }
                "Favorites" -> all.filter { it.isFavorite }
                else -> all
            }
            if (filtered.isEmpty()) {
                snackbarHostState.showSnackbar("这个分类下还没有歌")
            } else {
                // 随机 tile is always a shuffle by definition; otherwise honor
                // whatever play mode is currently set (the "顺序播放" cycle
                // button in Now Playing) instead of silently randomizing the
                // order regardless — that was the "I set sequential and it's
                // still random" bug.
                val shuffleActive = mood == "Random" || PlayerController.playMode.value == PlayMode.SHUFFLE
                // 激情/平静 put favorited songs first (already true in either
                // order — [all] is alphabetically sorted, so a non-shuffled
                // pass keeps that order within each half) so hearting a song
                // actually surfaces it sooner instead of landing anywhere by
                // chance. 收藏/随机 don't need this split — one's already
                // all-favorites, the other's meant to be a flat shuffle.
                val ordered = if (mood == "Energetic" || mood == "Calm") {
                    val (favorites, rest) = filtered.partition { it.isFavorite }
                    if (shuffleActive) favorites.shuffled() + rest.shuffled() else favorites + rest
                } else if (shuffleActive) {
                    filtered.shuffled()
                } else {
                    filtered
                }
                val queueSongs = if (mood == "Random") ordered.take(50) else ordered
                PlayerController.setQueueAndPlay(config, queueSongs, shuffled = shuffleActive, moodTag = mood)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Music", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showQueue = true }) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "播放队列")
                    }
                    IconButton(onClick = { showSearch = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MoodTile(
                title = "激情",
                subtitle = "Energetic · high energy",
                gradientStart = MoodColors.EnergeticStart,
                gradientEnd = MoodColors.EnergeticEnd,
                modifier = Modifier.fillMaxWidth().weight(1f),
                active = activeMood == "Energetic",
                onClick = { playMood("Energetic") }
            )
            MoodTile(
                title = "平静",
                subtitle = "Calm · slow it down",
                gradientStart = MoodColors.CalmStart,
                gradientEnd = MoodColors.CalmEnd,
                modifier = Modifier.fillMaxWidth().weight(1f),
                active = activeMood == "Calm",
                onClick = { playMood("Calm") }
            )
            MoodTile(
                title = "收藏",
                subtitle = "Favorites · what I'm into right now",
                gradientStart = MoodColors.FavoritesStart,
                gradientEnd = MoodColors.FavoritesEnd,
                modifier = Modifier.fillMaxWidth().weight(1f),
                active = activeMood == "Favorites",
                onClick = { playMood("Favorites") }
            )
            MoodTile(
                title = "随机",
                subtitle = "Random · surprise me",
                gradientStart = MoodColors.RandomStart,
                gradientEnd = MoodColors.RandomEnd,
                modifier = Modifier.fillMaxWidth().weight(1f),
                active = activeMood == "Random",
                onClick = { playMood("Random") }
            )
        }
    }

    if (showSearch) {
        SongSearchOverlay(
            songs = sortedLibrary,
            onDismiss = { showSearch = false },
            onSongSelected = { song ->
                if (config.isConfigured || isUsbActive) {
                    if (PlayerController.playMode.value == PlayMode.SHUFFLE) {
                        val rest = sortedLibrary.filter { it.id != song.id }.shuffled()
                        PlayerController.setQueueAndPlay(config, listOf(song) + rest, 0, shuffled = true)
                    } else {
                        val index = sortedLibrary.indexOf(song).coerceAtLeast(0)
                        PlayerController.setQueueAndPlay(config, sortedLibrary, index, shuffled = false)
                    }
                }
                showSearch = false
            }
        )
    }

    if (showQueue) {
        QueueSheet(onDismiss = { showQueue = false })
    }
}
