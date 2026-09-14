package com.example.music.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Wraps the real ExoPlayer for the [MediaSession] so headset buttons,
 * Bluetooth AVRCP, and lock-screen "skip" controls go through
 * [PlayerController]'s own queue instead of ExoPlayer's native playlist.
 *
 * PlayerController never actually loads a real multi-item ExoPlayer
 * playlist — it swaps a single MediaItem per song (see playAt) so it can
 * route each one through the disk cache/prefetch logic. That means the raw
 * ExoPlayer always reports hasNextMediaItem() == false, so the system's
 * default "skip to next" handling would silently no-op.
 *
 * Overriding seekToNext()/hasNextMediaItem() alone isn't enough, though —
 * the headset/lock-screen "next" button is gated on
 * getAvailableCommands().contains(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM), which by
 * default still reflects the underlying (always single-item) ExoPlayer and
 * so reports next as unavailable, silently dropping the button press before
 * seekToNext() is ever called. "previous" worked without this because
 * ExoPlayer always considers COMMAND_SEEK_TO_PREVIOUS available (it can
 * always at least restart the current item). Must also override
 * getAvailableCommands() to advertise next/previous based on the real queue.
 */
class QueueAwarePlayer(player: Player) : ForwardingPlayer(player) {
    override fun hasNextMediaItem(): Boolean = PlayerController.hasNext()
    override fun hasPreviousMediaItem(): Boolean = true
    override fun seekToNext() = PlayerController.skipToNext()
    override fun seekToNextMediaItem() = PlayerController.skipToNext()
    override fun seekToPrevious() = PlayerController.skipToPrevious()
    override fun seekToPreviousMediaItem() = PlayerController.skipToPrevious()

    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands().buildUpon()
        if (PlayerController.hasNext()) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            builder.remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        builder.add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        return builder.build()
    }
}
