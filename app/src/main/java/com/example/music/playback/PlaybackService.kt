package com.example.music.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Exposes [PlayerController]'s ExoPlayer to the rest of the system — headset
 * buttons, Bluetooth AVRCP, Android Auto/lock-screen controls — via a
 * MediaSession. This is what makes play/pause/next/previous work from
 * outside the app UI; MediaSessionService also handles turning the
 * foreground-service notification on/off automatically based on playback
 * state, so there's no manual notification code here.
 *
 * Doesn't own the player's lifecycle — PlayerController does (created in
 * MainActivity, released in its onDestroy). This service just wraps
 * whatever player already exists in a session and lets it go when the
 * service is destroyed.
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerController.getPlayer() ?: run {
            stopSelf()
            return
        }
        mediaSession = MediaSession.Builder(this, QueueAwarePlayer(player)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
