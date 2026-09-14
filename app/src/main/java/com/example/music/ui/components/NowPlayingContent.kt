package com.example.music.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.music.data.ServerConfig
import com.example.music.data.SettingsRepository
import com.example.music.data.Song
import com.example.music.data.SongRepository
import com.example.music.playback.PlayMode
import com.example.music.playback.PlayerController
import com.example.music.ui.theme.MoodColors
import kotlinx.coroutines.launch

/**
 * The actual "now playing" UI — cover, title/artist, scrubber, favorite, and
 * transport controls. Shared by [NowPlayingSheet] (in-app, wrapped in a
 * ModalBottomSheet) and the floating-capsule's expanded big card (hosted in
 * a WindowManager overlay via a plain ComposeView), so the two don't drift
 * into two different UIs over time.
 *
 * No real album art fetching yet (that needs a Subsonic getCoverArt call +
 * an image loading dependency, a separate piece of work), so the cover is a
 * placeholder disc colored by the song's category, same visual language as
 * the Home mood tiles rather than a generic gray box.
 *
 * [onOpenApp], when non-null, shows an extra "open app" button — used by the
 * overlay card (which otherwise has no way to get back into the full app);
 * the in-app sheet passes null since you're already there.
 */
@Composable
fun NowPlayingContent(
    modifier: Modifier = Modifier,
    onOpenApp: (() -> Unit)? = null,
    onShowQueue: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val config by settingsRepository.config.collectAsState(initial = ServerConfig())
    val currentSong by PlayerController.currentSong.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val playMode by PlayerController.playMode.collectAsState()
    val positionMs by PlayerController.currentPositionMs.collectAsState()
    val durationMs by PlayerController.durationMs.collectAsState()
    val library by SongRepository.library.collectAsState()
    val scope = rememberCoroutineScope()

    val song = currentSong ?: return
    // currentSong is a snapshot from whenever the queue was built — look up
    // the live copy so the heart reflects favorite-toggles made elsewhere.
    val liveSong = library.find { it.id == song.id } ?: song

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (onOpenApp != null || onShowQueue != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onShowQueue != null) {
                    IconButton(onClick = onShowQueue) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "播放队列", modifier = Modifier.size(20.dp))
                    }
                }
                if (onOpenApp != null) {
                    IconButton(onClick = onOpenApp) {
                        Icon(Icons.Filled.OpenInFull, contentDescription = "打开 App", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        val infiniteTransition = rememberInfiniteTransition(label = "cdSpin")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(9000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .aspectRatio(1f)
                .graphicsLayer { rotationZ = rotation }
                .clip(CircleShape)
                .background(Brush.sweepGradient(coverGradientFor(liveSong).let { it + it.reversed() })),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.34f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize(0.09f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            )
        }

        Spacer(Modifier.height(28.dp))

        Text(
            liveSong.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (liveSong.artist.isNotBlank()) {
            Text(
                liveSong.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        var dragPositionMs by remember { mutableStateOf<Float?>(null) }
        val durationF = durationMs.toFloat().coerceAtLeast(1f)
        Slider(
            value = (dragPositionMs ?: positionMs.toFloat()).coerceIn(0f, durationF),
            onValueChange = { dragPositionMs = it },
            onValueChangeFinished = {
                dragPositionMs?.let { PlayerController.seekTo(it.toLong()) }
                dragPositionMs = null
            },
            valueRange = 0f..durationF,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((dragPositionMs?.toLong()) ?: positionMs), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconButton(onClick = { PlayerController.cyclePlayMode() }) {
                val (icon, label) = when (playMode) {
                    PlayMode.SEQUENTIAL -> Icons.Filled.Repeat to "顺序播放，点击切换"
                    PlayMode.REPEAT_ONE -> Icons.Filled.RepeatOne to "单曲循环，点击切换"
                    PlayMode.SHUFFLE -> Icons.Filled.Shuffle to "随机播放，点击切换"
                }
                Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { PlayerController.skipToPrevious() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { PlayerController.togglePlayPause() }, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (isPlaying) Icons.Filled.PauseCircleFilled else Icons.Filled.PlayCircleFilled,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(72.dp)
                )
            }
            IconButton(onClick = { PlayerController.skipToNext() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首", modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { scope.launch { SongRepository.toggleFavorite(context, config, liveSong) } }) {
                Icon(
                    if (liveSong.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (liveSong.isFavorite) Color(0xFFF06AA0) else LocalContentColor.current,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

internal fun coverGradientFor(song: Song): List<Color> = when {
    song.isFavorite -> listOf(MoodColors.FavoritesStart, MoodColors.FavoritesEnd)
    song.genre.equals("Energetic", ignoreCase = true) -> listOf(MoodColors.EnergeticStart, MoodColors.EnergeticEnd)
    song.genre.equals("Calm", ignoreCase = true) -> listOf(MoodColors.CalmStart, MoodColors.CalmEnd)
    else -> listOf(MoodColors.RandomStart, MoodColors.RandomEnd)
}

internal fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
