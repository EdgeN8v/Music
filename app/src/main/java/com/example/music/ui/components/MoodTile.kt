package com.example.music.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One full-width mood tile on the Home screen (激情 / 平静 / 收藏 / 随机).
 * Presses gently scale down with a spring for a tactile feel, matching the
 * "smooth, natural transitions" requirement.
 *
 * [active] marks that this specific tile is the source of what's currently
 * playing, so it's obvious at a glance which one — a white outline that
 * gently breathes in and out, plus a small pulsing equalizer badge, rather
 * than a static highlight. The pulse isn't audio-reactive (that'd need real
 * FFT analysis of the stream), just a steady rhythmic loop meant to read as
 * "alive" instead of "selected."
 */
@Composable
fun MoodTile(
    title: String,
    subtitle: String,
    gradientStart: Color,
    gradientEnd: Color,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "tileScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "moodPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // A small breathing bump on top of the press scale, only while this
    // tile is the one playing — that's the "律动" feel, not a busy constant
    // animation on every tile.
    val scale = pressScale * (1f + if (active) pulse * 0.015f else 0f)
    val borderAlpha = if (active) 0.55f + pulse * 0.45f else 0f
    // White reads great in dark mode (sharp against a dark page). In light
    // mode a dark/black ring was tried and technically had contrast, but
    // looked harsh — a warm, natural-feeling yellow against a light page
    // reads as a deliberate "now playing" glow instead.
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val borderColor = if (isLightTheme) Color(0xFFFFD54F) else Color.White

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(gradientStart, gradientEnd)))
            .then(
                if (active) {
                    Modifier.border(3.dp, borderColor.copy(alpha = borderAlpha), RoundedCornerShape(24.dp))
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (active) "正在播放中" else subtitle,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )
        }
        if (active) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = "正在播放",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(24.dp)
                    .scale(1f + pulse * 0.3f)
            )
        }
    }
}
