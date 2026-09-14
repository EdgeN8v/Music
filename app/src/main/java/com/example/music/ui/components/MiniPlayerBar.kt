package com.example.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
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
import com.example.music.playback.PlayMode
import com.example.music.playback.PlayerController

/**
 * Now-playing strip shown above the bottom nav bar on every screen whenever
 * something is loaded into the player — title/artist, a scrubber, transport
 * controls, and (leftmost) the 顺序/单曲循环/随机 cycle button, one tap to
 * advance it. Used to also open a bigger "now playing" card on tap, but that
 * card was just a bigger, mostly-redundant version of what's already here
 * (Library already covers "find/favorite a song", the queue button in
 * Home/Library's top bar covers "what's next") — removed rather than kept
 * as a second thing to maintain.
 *
 * Renders nothing (zero height) when nothing is loaded yet.
 */
@Composable
fun MiniPlayerBar() {
    val currentSong by PlayerController.currentSong.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val playMode by PlayerController.playMode.collectAsState()
    val positionMs by PlayerController.currentPositionMs.collectAsState()
    val durationMs by PlayerController.durationMs.collectAsState()

    val song = currentSong ?: return

    // While dragging, follow the finger instead of the real playback
    // position (which would otherwise fight the drag every 500ms).
    var dragPositionMs by remember { mutableStateOf<Float?>(null) }

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
            IconButton(onClick = { PlayerController.cyclePlayMode() }) {
                val (icon, label) = when (playMode) {
                    PlayMode.SEQUENTIAL -> Icons.Filled.Repeat to "顺序播放，点击切换"
                    PlayMode.REPEAT_ONE -> Icons.Filled.RepeatOne to "单曲循环，点击切换"
                    PlayMode.SHUFFLE -> Icons.Filled.Shuffle to "随机播放，点击切换"
                }
                Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
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
}
