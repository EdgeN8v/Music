package com.example.music.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

enum class AppDestination(val route: String, val label: String) {
    Home("home", "Home"),
    Library("library", "Library"),
    Settings("settings", "Settings")
}

private const val DOUBLE_TAP_WINDOW_MS = 350L

@Composable
fun MusicBottomNavBar(
    current: AppDestination,
    onNavigate: (AppDestination) -> Unit,
    onLibraryDoubleTap: () -> Unit = {}
) {
    var lastLibraryTapAt by remember { mutableStateOf(0L) }
    NavigationBar {
        NavigationBarItem(
            selected = current == AppDestination.Home,
            onClick = { onNavigate(AppDestination.Home) },
            icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
            label = { Text("Home") }
        )
        NavigationBarItem(
            selected = current == AppDestination.Library,
            onClick = {
                val now = System.currentTimeMillis()
                if (now - lastLibraryTapAt < DOUBLE_TAP_WINDOW_MS) onLibraryDoubleTap()
                lastLibraryTapAt = now
                onNavigate(AppDestination.Library)
            },
            icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = "Library") },
            label = { Text("Library") }
        )
        NavigationBarItem(
            selected = current == AppDestination.Settings,
            onClick = { onNavigate(AppDestination.Settings) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
            label = { Text("Settings") }
        )
    }
}
