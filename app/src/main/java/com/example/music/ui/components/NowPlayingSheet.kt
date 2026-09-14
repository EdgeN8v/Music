package com.example.music.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.music.playback.PlayerController

/**
 * In-app "now playing" sheet — opened by tapping the mini player. The
 * actual content (cover/scrubber/controls) lives in [NowPlayingContent],
 * shared with the floating-capsule's overlay big card so the two UIs can't
 * drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(onDismiss: () -> Unit) {
    val currentSong by PlayerController.currentSong.collectAsState()
    if (currentSong == null) {
        onDismiss()
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showQueue by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        NowPlayingContent(onShowQueue = { showQueue = true })
    }

    if (showQueue) {
        QueueSheet(onDismiss = { showQueue = false })
    }
}
