package com.example.music.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache of the last successfully fetched network library, keyed per
 * server (url+username) so switching Navidrome accounts doesn't show stale
 * data from a different one.
 *
 * The point: a cold app start can render (and let Home's search/mood tiles
 * use) a song list the instant the app opens, instead of every launch
 * waiting on a fresh paginated search3 crawl over the network first —
 * SongRepository reads this synchronously before kicking off that fetch in
 * the background, then overwrites it once the fresh result lands.
 */
object LibraryCache {
    private fun cacheFile(context: Context, config: ServerConfig): File {
        val key = MessageDigest.getInstance("MD5")
            .digest("${config.url}|${config.username}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "library_cache_$key.json")
    }

    suspend fun read(context: Context, config: ServerConfig): List<Song>? = withContext(Dispatchers.IO) {
        try {
            val file = cacheFile(context, config)
            if (!file.exists()) return@withContext null
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                Song(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    genre = obj.optString("genre").ifBlank { null },
                    isFavorite = obj.optBoolean("isFavorite", false)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun write(context: Context, config: ServerConfig, songs: List<Song>) = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            songs.forEach { song ->
                arr.put(
                    JSONObject().apply {
                        put("id", song.id)
                        put("title", song.title)
                        put("artist", song.artist)
                        put("genre", song.genre ?: "")
                        put("isFavorite", song.isFavorite)
                    }
                )
            }
            cacheFile(context, config).writeText(arr.toString())
        } catch (e: Exception) {
            // Best-effort only — a failed write just means the next cold start won't have a warm cache.
        }
    }
}
