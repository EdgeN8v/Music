package com.example.music.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.music.data.MusicSource
import com.example.music.data.ServerConfig
import com.example.music.data.SettingsRepository
import com.example.music.data.Song
import com.example.music.data.SongRepository
import com.example.music.data.UsbLibrarySource
import com.example.music.playback.PlayMode
import com.example.music.playback.PlayerController
import com.example.music.ui.LibraryScrollEvents
import com.example.music.ui.components.QueueSheet
import com.example.music.ui.components.SongSearchOverlay
import com.example.music.ui.theme.MoodColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed class LibraryListItem {
    data class Header(val letter: String) : LibraryListItem()
    data class SongItem(val song: Song) : LibraryListItem()
}

/** How many songs ahead in the queue still get a numbered badge in Library — see upcomingPositions. */
private const val UPCOMING_HORIZON = 5

/**
 * Scrolls so the target item lands roughly in the middle of the visible
 * list instead of pinned to the very top — top-aligned meant it landed
 * right under the alphabet index bar / top edge, easy to mistake for not
 * having scrolled at all and awkward to tap.
 */
private suspend fun LazyListState.scrollToCentered(index: Int) {
    scrollToItem(index)
    val halfViewport = layoutInfo.viewportSize.height / 2f
    if (halfViewport > 0f) animateScrollBy(-halfViewport)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val config by settingsRepository.config.collectAsState(initial = ServerConfig())
    val songs by SongRepository.library.collectAsState()
    val indexedSongs by SongRepository.indexedLibrary.collectAsState()
    val isLoading by SongRepository.isLoading.collectAsState()
    val error by SongRepository.error.collectAsState()
    val currentSong by PlayerController.currentSong.collectAsState()
    val playQueue by PlayerController.queue.collectAsState()
    val playQueueIndex by PlayerController.currentIndex.collectAsState()
    val isUsbActive by MusicSource.isUsbActive.collectAsState()
    val usbPresent by MusicSource.usbPresent.collectAsState()
    val usbTreeUri by MusicSource.usbTreeUri.collectAsState()

    var showSearch by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    val usbAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        if (!UsbLibrarySource.takePersistableAccess(context, uri)) {
            scope.launch { snackbarHostState.showSnackbar("U 盘授权失败，请重试") }
        }
    }

    LaunchedEffect(config, isUsbActive) {
        MusicSource.ensureLoaded(config)
    }

    // Already sorted A→Z and letter-indexed once by SongRepository when it
    // loaded — grouping here is just a cheap pass over that, no re-sorting
    // or re-transliterating on every screen visit.
    val sortedSongs = songs

    val groupedItems = remember(indexedSongs) {
        buildList {
            var lastLetter: String? = null
            indexedSongs.forEach { indexed ->
                if (indexed.letter != lastLetter) {
                    add(LibraryListItem.Header(indexed.letter))
                    lastLetter = indexed.letter
                }
                add(LibraryListItem.SongItem(indexed.song))
            }
        }
    }

    val headerPositions = remember(groupedItems) {
        val map = LinkedHashMap<String, Int>()
        groupedItems.forEachIndexed { i, item ->
            if (item is LibraryListItem.Header) map[item.letter] = i
        }
        map
    }

    val explicitNextIds by PlayerController.explicitNextIds.collectAsState()

    // "This song plays soon" badges — capped to a short lookahead instead of
    // numbering the entire remaining queue (which, for a shuffled mood queue,
    // could be hundreds of songs deep and was just visual noise). Explicit
    // "播放下一首" adds get their own marker in SongRow regardless of this
    // cap, since those you deliberately chose and always want to see.
    val upcomingPositions = remember(playQueue, playQueueIndex) {
        val map = HashMap<String, Int>()
        val horizonEnd = (playQueueIndex + 1 + UPCOMING_HORIZON).coerceAtMost(playQueue.size)
        for (i in (playQueueIndex + 1) until horizonEnd) {
            map.putIfAbsent(playQueue[i].id, i - playQueueIndex)
        }
        map
    }

    // Whichever row we last scrolled-to gets a couple of quick highlight
    // pulses so it's easy to spot among a screenful of near-identical rows —
    // a plain scroll-and-center wasn't enough to actually notice which one
    // you landed on. Single shared Animatable: only the one row whose id
    // matches actually reads .value each frame (see SongRow's flashProgress
    // param), so this doesn't recompose the whole list.
    var flashSongId by remember { mutableStateOf<String?>(null) }
    val flashAlpha = remember { Animatable(0f) }
    var flashJob by remember { mutableStateOf<Job?>(null) }
    fun flashRow(songId: String) {
        flashJob?.cancel()
        flashSongId = songId
        flashJob = scope.launch {
            repeat(2) {
                flashAlpha.snapTo(0f)
                flashAlpha.animateTo(1f, tween(150))
                flashAlpha.animateTo(0f, tween(150))
            }
            flashSongId = null
        }
    }

    fun scrollToSong(song: Song) {
        val position = groupedItems.indexOfFirst { it is LibraryListItem.SongItem && it.song.id == song.id }
        if (position >= 0) {
            scope.launch {
                listState.scrollToCentered(position)
                flashRow(song.id)
            }
        }
    }

    // Jump straight to whatever's currently playing every time Library is
    // opened, so you don't have to scroll to find it — waits for the list to
    // actually have content (it may still be loading the first time).
    LaunchedEffect(groupedItems.isNotEmpty()) {
        val target = currentSong ?: return@LaunchedEffect
        val position = groupedItems.indexOfFirst { it is LibraryListItem.SongItem && it.song.id == target.id }
        if (position >= 0) {
            listState.scrollToCentered(position)
            flashRow(target.id)
        }
    }

    // Double-tapping the Library tab in the bottom nav doesn't recompose this
    // screen (it's already the selected/current one), so the effect above
    // never re-runs on its own — this collector is the on-demand equivalent,
    // fired from BottomNavBar via AppNavigation. rememberUpdatedState keeps
    // this long-lived collector reading fresh values instead of whatever
    // groupedItems/currentSong looked like when it first launched.
    val latestGroupedItems by rememberUpdatedState(groupedItems)
    val latestCurrentSong by rememberUpdatedState(currentSong)
    LaunchedEffect(Unit) {
        LibraryScrollEvents.scrollToCurrent.collect {
            val target = latestCurrentSong ?: return@collect
            val position = latestGroupedItems.indexOfFirst { it is LibraryListItem.SongItem && it.song.id == target.id }
            if (position >= 0) {
                listState.scrollToCentered(position)
                flashRow(target.id)
            }
        }
    }

    fun playSong(song: Song) {
        // Honor whatever play mode is set (the cycle button in Now Playing)
        // instead of always playing the alphabetical list in order — tapping
        // a row while shuffle mode is on shuffles everything else, tapped
        // song first.
        if (PlayerController.playMode.value == PlayMode.SHUFFLE) {
            val rest = sortedSongs.filter { it.id != song.id }.shuffled()
            PlayerController.setQueueAndPlay(config, listOf(song) + rest, 0, shuffled = true)
        } else {
            val index = sortedSongs.indexOf(song).coerceAtLeast(0)
            PlayerController.setQueueAndPlay(config, sortedSongs, index, shuffled = false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isUsbActive) "Library · U盘" else "Library") },
                actions = {
                    IconButton(
                        onClick = { scope.launch { MusicSource.forceReload(config) } },
                        enabled = (isUsbActive || config.isConfigured) && !isLoading
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showQueue = true }) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "播放队列")
                    }
                    IconButton(onClick = { showSearch = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (usbPresent && usbTreeUri == null) {
                UsbAccessBanner(onGrantClick = { usbAccessLauncher.launch(UsbLibrarySource.createAccessIntent(context)) })
            }
            Box(modifier = Modifier.weight(1f)) {
            when {
                !config.isConfigured && !isUsbActive -> Text(
                    "先去 Settings 里填好 Navidrome 的地址、账号和密码，或者插入 U 盘并授权访问",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                isLoading && songs.isEmpty() -> {
                    // Nothing — loads are fast enough now (and the crash/cancellation
                    // bugs are fixed) that a loading indicator here was more visual
                    // noise than useful signal.
                }
                error != null && songs.isEmpty() -> Text(
                    "加载失败：$error",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    color = MaterialTheme.colorScheme.error
                )
                songs.isEmpty() -> Text(
                    "库是空的",
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        groupedItems.forEach { entry ->
                            when (entry) {
                                is LibraryListItem.Header -> stickyHeader(key = "header_${entry.letter}") {
                                    LetterHeader(entry.letter)
                                }
                                is LibraryListItem.SongItem -> item(key = entry.song.id) {
                                    val song = entry.song
                                    SongRow(
                                        song = song,
                                        isCurrent = song.id == currentSong?.id,
                                        queuePosition = upcomingPositions[song.id],
                                        isExplicitNext = song.id in explicitNextIds,
                                        flashProgress = if (song.id == flashSongId) flashAlpha.value else 0f,
                                        onRowClick = { playSong(song) },
                                        onPlayNextClick = {
                                            PlayerController.playNext(config, song)
                                            scope.launch { snackbarHostState.showSnackbar("已加入下一首播放：${song.title}") }
                                        },
                                        onFavoriteToggle = {
                                            scope.launch { SongRepository.toggleFavorite(context, config, song) }
                                        },
                                        onMoodPick = { mood ->
                                            scope.launch { SongRepository.setMoodLabel(context, song, mood) }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    AlphabetIndexBar(
                        letters = headerPositions.keys.toList(),
                        onLetterSelected = { letter ->
                            headerPositions[letter]?.let { position ->
                                scope.launch { listState.animateScrollToItem(position) }
                            }
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(28.dp)
                            .padding(vertical = 8.dp)
                    )
                }
            }
            }
        }
    }

    if (showSearch) {
        SongSearchOverlay(
            songs = sortedSongs,
            onDismiss = { showSearch = false },
            onSongSelected = { song ->
                // Just jump to it in the list — don't assume "play now". You might
                // want "play next" instead, which needs the row's own button anyway.
                showSearch = false
                scrollToSong(song)
            }
        )
    }

    if (showQueue) {
        QueueSheet(onDismiss = { showQueue = false })
    }
}

/** Shown when a USB drive is plugged in but we don't have permission to browse it yet. */
@Composable
private fun UsbAccessBanner(onGrantClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "检测到 U 盘，点击授权访问",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        androidx.compose.material3.TextButton(onClick = onGrantClick) {
            Text("授权")
        }
    }
}

@Composable
private fun LetterHeader(letter: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * At-a-glance status dot for a song's Energetic/Calm classification — filled
 * red/blue (matching the Home mood tiles) when it has one, a faint hollow
 * ring when it doesn't. The ring (rather than nothing) matters: an empty
 * spot would be easy to mistake for "hasn't loaded yet" instead of "not
 * classified" — you should be able to tell every song's status at a glance
 * without long-pressing each one to check.
 */
@Composable
private fun MoodDot(genre: String?, modifier: Modifier = Modifier) {
    val color = when {
        genre.equals("Energetic", ignoreCase = true) -> MoodColors.EnergeticStart
        genre.equals("Calm", ignoreCase = true) -> MoodColors.CalmStart
        else -> null
    }
    Box(
        modifier = modifier
            .size(10.dp)
            .then(
                if (color != null) Modifier.background(color, CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
    )
}

/** Tap-to-jump A–Z strip along the trailing edge, contacts-app style. */
@Composable
private fun AlphabetIndexBar(
    letters: List<String>,
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        letters.forEach { letter ->
            Text(
                text = letter,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onLetterSelected(letter) }
                    .padding(vertical = 1.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    queuePosition: Int?,
    isExplicitNext: Boolean,
    flashProgress: Float,
    onRowClick: () -> Unit,
    onPlayNextClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onMoodPick: (String) -> Unit
) {
    var showMoodMenu by remember { mutableStateOf(false) }

    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
            )
            .background(MaterialTheme.colorScheme.primary.copy(alpha = flashProgress * 0.35f))
            .combinedClickable(onClick = onRowClick, onLongClick = { showMoodMenu = true })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isCurrent) {
            Icon(
                Icons.Filled.Equalizer,
                contentDescription = "正在播放",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 8.dp)
            )
        }
        MoodDot(genre = song.genre, modifier = Modifier.padding(end = 10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            if (song.artist.isNotBlank()) {
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isExplicitNext) {
            // Distinct from the ambient queue-position number below — this
            // is a song you explicitly chose with "播放下一首", so it always
            // gets its own marker regardless of how far off the natural
            // queue-position lookahead reaches.
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.secondary, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlaylistPlay,
                    contentDescription = "已加入下一首播放",
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else if (queuePosition != null) {
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    queuePosition.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        IconButton(onClick = onPlayNextClick) {
            Icon(
                Icons.Filled.PlaylistPlay,
                contentDescription = "Play next"
            )
        }

        IconButton(onClick = onFavoriteToggle) {
            Icon(
                imageVector = if (song.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (song.isFavorite) Color(0xFFF06AA0) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Long-press a row to fix a song's 激情/平静 classification (or pull it out
    // of both) straight from the phone — no need to plug into a PC and re-tag
    // the file just because the audio-analysis script guessed wrong.
    DropdownMenu(expanded = showMoodMenu, onDismissRequest = { showMoodMenu = false }) {
        DropdownMenuItem(
            text = { Text("标记为「激情」") },
            onClick = { onMoodPick("Energetic"); showMoodMenu = false }
        )
        DropdownMenuItem(
            text = { Text("标记为「平静」") },
            onClick = { onMoodPick("Calm"); showMoodMenu = false }
        )
        DropdownMenuItem(
            text = { Text("移除分类（不属于激情/平静）") },
            onClick = { onMoodPick(""); showMoodMenu = false }
        )
    }
    }
}
