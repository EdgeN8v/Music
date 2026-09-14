package com.example.music.ui.theme

import androidx.compose.ui.graphics.Color

// Neutral app palette (Material3 base)
val PrimaryLight = Color(0xFF3F4B5B)
val PrimaryDark = Color(0xFFB8C4D6)
val BackgroundLight = Color(0xFFF7F7F8)
val BackgroundDark = Color(0xFF121214)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF1C1C1F)

// Mood tile palette — used directly by the Home screen, independent of light/dark theme
// so the four tiles always read clearly regardless of system theme.
object MoodColors {
    val EnergeticStart = Color(0xFFFF5F5F) // 激情
    val EnergeticEnd = Color(0xFFE33A3A)

    val CalmStart = Color(0xFFAFC9D9) // 平静
    val CalmEnd = Color(0xFF89AFC4)

    val FavoritesStart = Color(0xFFFF9AC1) // 收藏
    val FavoritesEnd = Color(0xFFF06AA0)

    val RandomStart = Color(0xFF9B8CD9) // 随机
    val RandomEnd = Color(0xFF7566C2)
}
