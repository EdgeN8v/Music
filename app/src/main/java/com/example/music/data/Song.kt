package com.example.music.data

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val genre: String? = null,
    val isFavorite: Boolean = false,
    /** Set only for songs read off a USB drive — its content:// URI, playable directly, no server round-trip. */
    val localUri: String? = null
)
