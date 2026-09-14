package com.example.music.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.example.music.data.Song
import java.io.File

/**
 * Disk cache for streamed song bytes, shared by playback and prefetching.
 * Bounded by a least-recently-used evictor (default 500MB, user-adjustable
 * in Settings) so it can't grow without limit on a phone with limited
 * storage — once the cap is hit, the least recently played songs get
 * evicted to make room for new ones.
 *
 * Lives under the app's cache dir rather than files dir: it's disposable
 * data the OS is free to reclaim under storage pressure, which is exactly
 * what we want for "recently streamed songs," not permanent offline saves.
 */
object AudioCache {
    private var cache: SimpleCache? = null
    private var builtWithLimitMb: Int = -1

    @Synchronized
    fun get(context: Context, limitMb: Int): SimpleCache {
        cache?.let { if (builtWithLimitMb == limitMb) return it }
        cache?.release()
        val dir = File(context.applicationContext.cacheDir, "audio_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(limitMb.toLong() * 1024 * 1024)
        val db = StandaloneDatabaseProvider(context.applicationContext)
        return SimpleCache(dir, evictor, db).also {
            cache = it
            builtWithLimitMb = limitMb
        }
    }

    /** Total bytes currently held in the cache, or null if caching is off / not yet initialized. */
    @Synchronized
    fun currentUsageBytes(): Long? = cache?.cacheSpace

    /**
     * Removes cached audio for songs that are neither favorited nor tagged
     * Energetic/Calm — i.e. stuff that only ended up cached because 随机
     * happened to pick it, not because it's music you deliberately chose.
     * Songs no longer in [library] (e.g. deleted from the server) are also
     * treated as unprotected, since there's no way to check their category.
     * Returns the number of cache entries removed.
     */
    @Synchronized
    fun clearUnprotected(library: List<Song>): Int {
        val c = cache ?: return 0
        val byId = library.associateBy { it.id }
        var removed = 0
        for (key in c.keys.toList()) {
            val id = key.removePrefix("song:")
            val song = byId[id]
            val protected = song != null &&
                (song.isFavorite || song.genre.equals("Energetic", ignoreCase = true) || song.genre.equals("Calm", ignoreCase = true))
            if (!protected) {
                try {
                    c.removeResource(key)
                    removed++
                } catch (e: Exception) {
                    // Best-effort cleanup — leave it cached if removal fails.
                }
            }
        }
        return removed
    }
}
