package com.example.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.music.data.ServerConfig
import com.example.music.data.SettingsRepository
import com.example.music.data.Song
import com.example.music.data.SongRepository
import com.example.music.playback.PlayerController
import kotlinx.coroutines.launch

/**
 * The "何时会播什么" view the inline Library badges were standing in for.
 * Deliberately minimal, no title bar/section labels — just "now playing"
 * pinned at top (with its own favorite heart), then a flat, drag-to-reorder
 * list of what's coming up. Tap a row to jump straight to it, the heart
 * favorites it, the X removes it from the queue without playing it, and the
 * grip handle on the right drags it to a new position. The sheet itself
 * closes via the standard swipe-down/tap-outside gesture (its default drag
 * handle), not an explicit close button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        QueueContent()
    }
}

private val ROW_HEIGHT = 64.dp

@Composable
private fun QueueContent() {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val config by settingsRepository.config.collectAsState(initial = ServerConfig())
    val scope = rememberCoroutineScope()

    val queue by PlayerController.queue.collectAsState()
    val currentIndex by PlayerController.currentIndex.collectAsState()
    val library by SongRepository.library.collectAsState()

    fun live(song: Song): Song = library.find { it.id == song.id } ?: song

    val currentSong = queue.getOrNull(currentIndex)?.let(::live)
    val upcoming = remember(queue, currentIndex) {
        ((currentIndex + 1) until queue.size).map { it to queue[it] }
    }

    val density = LocalDensity.current
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetPx by remember { mutableStateOf(0f) }

    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(bottom = 16.dp)) {
        if (currentSong != null) {
            Row(
                modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Equalizer,
                    contentDescription = "正在播放",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                SongTexts(currentSong, modifier = Modifier.weight(1f).padding(start = 10.dp), emphasized = true)
                IconButton(onClick = { scope.launch { SongRepository.toggleFavorite(context, config, currentSong) } }) {
                    FavoriteIcon(currentSong.isFavorite)
                }
            }
            HorizontalDivider()
        }

        if (upcoming.isEmpty()) {
            Text(
                "队列已经放完了，会自动循环回到开头",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(upcoming, key = { (_, song) -> song.id }) { (index, song) ->
                    val liveSong = live(song)
                    val isDragging = draggingIndex == index
                    QueueRow(
                        song = liveSong,
                        offsetYPx = if (isDragging) dragOffsetPx else 0f,
                        elevated = isDragging,
                        onClick = { PlayerController.jumpToQueueIndex(index) },
                        onRemove = { PlayerController.removeFromQueue(index) },
                        onFavoriteToggle = { scope.launch { SongRepository.toggleFavorite(context, config, liveSong) } },
                        onDragStart = {
                            draggingIndex = index
                            dragOffsetPx = 0f
                        },
                        onDrag = { deltaY ->
                            dragOffsetPx += deltaY
                            val moveBy = (dragOffsetPx / rowHeightPx).toInt()
                            if (moveBy != 0) {
                                val from = draggingIndex ?: index
                                val to = (from + moveBy).coerceIn(currentIndex + 1, queue.lastIndex)
                                if (to != from) {
                                    PlayerController.moveQueueItem(from, to)
                                    draggingIndex = to
                                    dragOffsetPx -= moveBy * rowHeightPx
                                }
                            }
                        },
                        onDragEnd = {
                            draggingIndex = null
                            dragOffsetPx = 0f
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    offsetYPx: Float,
    elevated: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .graphicsLayer { translationY = offsetYPx }
            .then(if (elevated) Modifier.shadow(4.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongTexts(song, modifier = Modifier.weight(1f))
        }
        IconButton(onClick = onFavoriteToggle) {
            FavoriteIcon(song.isFavorite)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "从队列移除", modifier = Modifier.size(18.dp))
        }
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = "拖动排序",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { latestOnDragStart() },
                        onDragEnd = { latestOnDragEnd() },
                        onDragCancel = { latestOnDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            latestOnDrag(dragAmount.y)
                        }
                    )
                }
        )
    }
}

@Composable
private fun FavoriteIcon(isFavorite: Boolean) {
    Icon(
        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        contentDescription = "收藏",
        tint = if (isFavorite) Color(0xFFF06AA0) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
    )
}

@Composable
private fun SongTexts(song: Song, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Column(modifier = modifier) {
        Text(
            song.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (song.artist.isNotBlank()) {
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
