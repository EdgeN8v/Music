package com.example.music

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.music.data.MusicSource
import com.example.music.data.SettingsRepository
import com.example.music.playback.PlaybackService
import com.example.music.playback.PlayerController
import com.example.music.ui.navigation.AppNavigation
import com.example.music.ui.theme.AppThemeMode
import com.example.music.ui.theme.MusicTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // One-off, near-instant local reads (no network) — simpler than
        // threading an async settings load through player init / the
        // Settings screen's first composition for values only needed once,
        // at startup.
        val settingsRepository = SettingsRepository(applicationContext)
        val cacheSettings = runBlocking { settingsRepository.cacheSettings.first() }
        val initiallyConfigured = runBlocking { settingsRepository.config.first().isConfigured }
        PlayerController.init(applicationContext, cacheSettings.limitMb, cacheSettings.enabled)
        MusicSource.init(applicationContext)

        // Hosts the MediaSession that headset buttons / Bluetooth / lock-screen
        // controls talk to — see PlaybackService for why this is a separate service.
        //
        // Plain startService, NOT startForegroundService: the latter requires
        // the service to call startForeground() within a few seconds or the
        // OS kills the app (this was the "crashes ~30s after opening, whether
        // or not you've played anything" bug) — but MediaSessionService only
        // promotes itself to a real foreground service once playback actually
        // starts. Starting it plain lets it sit idle until then with no
        // deadline, which is the normal way to use this API.
        startService(Intent(this, PlaybackService::class.java))

        setContent {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val requestNotificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* denying this just means no visible notification — playback and media keys still work */ }
                DisposableEffect(Unit) {
                    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    onDispose {}
                }
            }

            // In-memory only for now — swap for a DataStore-backed preference
            // once settings persistence is wired up.
            var themeMode by remember { mutableStateOf(AppThemeMode.SYSTEM) }

            MusicTheme(themeMode = themeMode) {
                AppNavigation(
                    themeMode = themeMode,
                    onThemeChange = { themeMode = it },
                    initiallyConfigured = initiallyConfigured
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            PlayerController.release()
        }
    }
}
