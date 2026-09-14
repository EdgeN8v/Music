package com.example.music.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.music.data.MusicSource
import com.example.music.data.SettingsRepository
import com.example.music.data.SongRepository
import com.example.music.playback.PlayerController
import com.example.music.ui.LibraryScrollEvents
import com.example.music.ui.components.AppDestination
import com.example.music.ui.components.MiniPlayerBar
import com.example.music.ui.components.MusicBottomNavBar
import com.example.music.ui.screens.HomeScreen
import com.example.music.ui.screens.LibraryScreen
import com.example.music.ui.screens.SettingsScreen
import com.example.music.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun AppNavigation(
    themeMode: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    initiallyConfigured: Boolean = false
) {
    val navController: NavHostController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.Home.route
    val context = LocalContext.current

    // Resume last session's song/queue/position once, at app startup — needs
    // the library loaded first so saved song ids can be matched back to real
    // Song objects. Runs once per process (see PlayerController.restoreAttempted);
    // a 5s cap means a slow/unconfigured server just skips the resume instead
    // of hanging this effect forever.
    LaunchedEffect(Unit) {
        val settingsRepository = SettingsRepository(context)
        val config = settingsRepository.config.first()
        MusicSource.ensureLoaded(config)
        val songs = withTimeoutOrNull(5000) { SongRepository.library.first { it.isNotEmpty() } }
        if (songs != null) {
            PlayerController.restoreFromSavedStateIfNeeded(config, songs)
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                // Persistent now-playing strip: visible on every tab, shows what's
                // playing, whether it's paused, whether 随机/shuffle is active, and
                // gives prev/play/next without leaving the current screen.
                MiniPlayerBar()
                MusicBottomNavBar(
                    current = AppDestination.values().firstOrNull { it.route == currentRoute }
                        ?: AppDestination.Home,
                    onNavigate = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onLibraryDoubleTap = { LibraryScrollEvents.requestScrollToCurrent() }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        ) {
            composable(AppDestination.Home.route) {
                HomeScreen()
            }
            composable(AppDestination.Library.route) {
                LibraryScreen()
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen(
                    currentTheme = themeMode,
                    onThemeChange = onThemeChange,
                    initiallyConfigured = initiallyConfigured
                )
            }
        }
    }
}
