package com.example.music.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fired when the Library tab is double-tapped in the bottom nav. Library
 * already jumps to whatever's playing the moment it's freshly opened, but
 * tapping an already-selected tab doesn't recompose/relaunch that effect —
 * this gives a way to ask for the same jump on demand while already there.
 */
object LibraryScrollEvents {
    private val _scrollToCurrent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToCurrent: SharedFlow<Unit> = _scrollToCurrent.asSharedFlow()

    fun requestScrollToCurrent() {
        _scrollToCurrent.tryEmit(Unit)
    }
}
