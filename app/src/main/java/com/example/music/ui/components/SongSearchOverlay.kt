package com.example.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.music.data.SettingsRepository
import com.example.music.data.Song
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Pops up over whatever screen is showing: a small search field with the
 * keyboard opened automatically, results filtered live from [songs] by
 * title or artist, tap a result to play it via [onSongSelected].
 *
 * While the box is still empty it shows recent searches (tap one to re-run
 * it) plus a few suggested artists pulled from the library by song count,
 * instead of a blank page. A query only lands in history once it actually
 * led somewhere — picking/favoriting/queueing a result, or pressing the
 * keyboard's search key — not on every keystroke.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongSearchOverlay(
    songs: List<Song>,
    onDismiss: () -> Unit,
    onSongSelected: (Song) -> Unit,
    onFavoriteToggle: (Song) -> Unit = {},
    onPlayNext: (Song) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val history by settingsRepository.searchHistory.collectAsState(initial = emptyList())

    // UNDISPATCHED so the write has already started (and is inside
    // addSearchHistory's NonCancellable block) before this overlay gets
    // dismissed and its scope cancelled — a normally-dispatched launch could
    // be cancelled before it ever ran when picking a result closes the overlay.
    fun recordQuery() {
        val q = query
        scope.launch(start = CoroutineStart.UNDISPATCHED) { settingsRepository.addSearchHistory(q) }
    }

    // "Suggestions" without any listening-history data to go on: the artists
    // with the most songs in the library — a reasonable proxy for "what you
    // probably want to look for". Multi-artist credits ("A、B", "A&B") are
    // split so each person counts once per song. The pool is *every* artist in
    // the library, not just the most-represented ones — drawing only from a
    // top-N kept surfacing the same dominant artists and got stale. 12 are
    // picked at random each time the overlay opens, and 换一批 re-rolls them
    // on demand. Keyed on the pool's *contents* (not [songs] itself) so
    // toggling a favorite while searching doesn't reshuffle them.
    val artistPool = remember(songs) {
        songs.flatMap { song -> song.artist.split('、', '&').map { it.trim() } }
            .filter { it.isNotEmpty() && it.length <= 20 }
            .distinct()
    }
    var rerollKey by remember { mutableStateOf(0) }
    val suggestedArtists = remember(artistPool, rerollKey) { artistPool.shuffled().take(12) }

    val results = remember(query, songs) {
        if (query.isBlank()) {
            emptyList()
        } else {
            songs.filter {
                it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "关闭")
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                            .focusRequester(focusRequester),
                        placeholder = { Text("搜索歌曲名或歌手") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            recordQuery()
                            keyboardController?.hide()
                        })
                    )
                }

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }

                when {
                    query.isBlank() -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        if (history.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "最近搜索",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                )
                                TextButton(onClick = { scope.launch { settingsRepository.clearSearchHistory() } }) {
                                    Text("清空")
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                history.forEach { h ->
                                    SuggestionChip(onClick = { query = h }, label = { Text(h) })
                                }
                            }
                        }
                        if (suggestedArtists.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = if (history.isNotEmpty()) 12.dp else 0.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "试试搜这些歌手",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { rerollKey++ }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("换一批", modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                suggestedArtists.forEach { a ->
                                    SuggestionChip(onClick = { query = a }, label = { Text(a) })
                                }
                            }
                        }
                        if (history.isEmpty() && suggestedArtists.isEmpty()) {
                            Text("输入歌曲名或歌手试试", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    results.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有找到匹配的歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(results, key = { it.id }) { song ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            recordQuery()
                                            onSongSelected(song)
                                        }
                                        .padding(horizontal = 20.dp, vertical = 14.dp)
                                ) {
                                    Text(song.title, style = MaterialTheme.typography.bodyLarge)
                                    if (song.artist.isNotBlank()) {
                                        Text(
                                            song.artist,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    recordQuery()
                                    onPlayNext(song)
                                }) {
                                    Icon(Icons.Filled.PlaylistPlay, contentDescription = "播放下一首")
                                }
                                IconButton(onClick = {
                                    recordQuery()
                                    onFavoriteToggle(song)
                                }) {
                                    Icon(
                                        imageVector = if (song.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                        contentDescription = "收藏",
                                        tint = if (song.isFavorite) Color(0xFFF06AA0) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
