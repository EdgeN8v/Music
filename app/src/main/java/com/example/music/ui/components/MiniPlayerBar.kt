package com.example.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.music.playback.PlayerController

/**
 * Now-playing strip shown above the bottom nav bar on every screen whenever
 * something is loaded into the player, so it's always visible which song is
 * playing, whether it's paused, and whether 随机 (shuffle) mode is active —
 * plus previous/next so you're not stuck on one track.
 *
 * Renders nothing (zero height) when nothing is loaded yet.
 */
@Composable
fun MiniPlayerBar() {
    val currentSong by PlayerController.currentSong.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val isShuffled by PlayerController.isShuffled.collectAsState()
    val positionMs by PlayerController.currentPositionMs.collectAsState()
    val durationMs by PlayerController.durationMs.collectAsState()

    val song = currentSong ?: return

    // While dragging, follow the finger instead of the real playback
    // position (which would otherwise fight the drag every 500ms).
    var dragPositionMs by remember { mutableStateOf<Float?>(null) }
    var showNowPlaying by remember { mutableStateOf(false) }

    Column {
        HorizontalDivider()
        if (durationMs > 0) {
            Slider(
                value = (dragPositionMs ?: positionMs.toFloat()).coerceIn(0f, durationMs.toFloat()),
                onValueChange = { dragPositionMs = it },
                onValueChangeFinished = {
                    dragPositionMs?.let { PlayerController.seekTo(it.toLong()) }
                    dragPositionMs = null
                },
                valueRange = 0f..durationMs.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .padding(horizontal = 12.dp),
                colors = SliderDefaults.colors()
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isShuffled) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = "随机播放中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(start = 4.dp, end = 6.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showNowPlaying = true }
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (song.artist.isNotBlank()) {
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { PlayerController.skipToPrevious() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
            }
            IconButton(onClick = { PlayerController.togglePlayPause() }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.PauseCircleFilled else Icons.Filled.PlayCircleFilled,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(36.dp)
                )
            }
            IconButton(onClick = { PlayerController.skipToNext() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
            }
        }
    }

    if (showNowPlaying) {
        NowPlayingSheet(onDismiss = { showNowPlaying = false })
    }
}
