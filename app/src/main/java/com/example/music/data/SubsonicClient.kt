package com.example.music.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Minimal Subsonic API client (works against Navidrome, which implements the
 * Subsonic protocol). Uses token-based auth (md5(password + salt)) so the
 * raw password never goes on the wire, and asks the server for JSON
 * responses (f=json) instead of the default XML.
 *
 * Reference: http://www.subsonic.org/pages/api.jsp
 */
object SubsonicClient {

    private const val CLIENT_NAME = "music-minimal"
    private const val API_VERSION = "1.16.1"

    sealed class ApiResult<out T> {
        data class Success<T>(val data: T) : ApiResult<T>()
        data class Failure(val message: String) : ApiResult<Nothing>()
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomSalt(length: Int = 8): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    /** Builds a full request URL for a Subsonic REST endpoint with auth params attached. */
    private fun buildUrl(config: ServerConfig, endpoint: String, extraParams: Map<String, String> = emptyMap()): String {
        val salt = randomSalt()
        val token = md5Hex(config.password + salt)
        val base = config.url.trimEnd('/')
        val params = mutableMapOf(
            "u" to config.username,
            "t" to token,
            "s" to salt,
            "v" to API_VERSION,
            "c" to CLIENT_NAME,
            "f" to "json"
        )
        params.putAll(extraParams)
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$base/rest/$endpoint?$query"
    }

    /** The URL ExoPlayer should stream directly from. Includes auth params. */
    fun streamUrl(config: ServerConfig, songId: String): String =
        buildUrl(config, "stream", mapOf("id" to songId))

    private suspend fun getJson(url: String): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"

            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext ApiResult.Failure("HTTP $code")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body).getJSONObject("subsonic-response")
            val status = root.optString("status")
            if (status != "ok") {
                val error = root.optJSONObject("error")
                val msg = error?.optString("message") ?: "Unknown Subsonic error"
                return@withContext ApiResult.Failure(msg)
            }
            ApiResult.Success(root)
        } catch (e: CancellationException) {
            // Not a real failure — the caller (e.g. a screen you navigated away
            // from mid-load) stopped waiting. Must propagate, not swallow, or
            // structured concurrency breaks and this surfaces as a bogus
            // "Network error: the coroutine scope left the composition".
            throw e
        } catch (e: Exception) {
            ApiResult.Failure(e.message ?: "Network error")
        }
    }

    suspend fun ping(config: ServerConfig): ApiResult<Unit> {
        val result = getJson(buildUrl(config, "ping"))
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }

    /**
     * Navidrome fills in a literal "[Unknown Artist]" (and similarly for
     * album) when a track has an ID3 tag container but no artist frame in
     * it — as opposed to no tag at all, where it just leaves the field
     * blank. Screens want "nothing" either way (no artist line at all), so
     * that placeholder gets normalized to "" right here rather than special-
     * cased in every UI that reads [Song.artist].
     */
    private fun cleanArtist(raw: String): String =
        raw.takeUnless { it == "[Unknown Artist]" } ?: ""

    private fun parseSongsArray(array: JSONArray): List<Song> {
        val songs = mutableListOf<Song>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            songs.add(
                Song(
                    id = obj.optString("id"),
                    title = obj.optString("title", "未知标题"),
                    artist = cleanArtist(obj.optString("artist", "")),
                    genre = obj.optString("genre").ifBlank { null },
                    isFavorite = obj.has("starred")
                )
            )
        }
        return songs
    }

    /**
     * Pages through search3 with an empty query — Navidrome treats that as
     * "match everything" — to pull the whole library instead of a random
     * sample. Stops once a page comes back short (end of library) or the
     * safety cap is hit, so one huge library can't loop forever on a slow
     * connection.
     */
    suspend fun getAllSongs(config: ServerConfig, pageSize: Int = 500, maxSongs: Int = 10000): ApiResult<List<Song>> {
        val songs = mutableListOf<Song>()
        var offset = 0
        while (offset < maxSongs) {
            val result = getJson(
                buildUrl(
                    config,
                    "search3",
                    mapOf(
                        "query" to "",
                        "artistCount" to "0",
                        "albumCount" to "0",
                        "songCount" to pageSize.toString(),
                        "songOffset" to offset.toString()
                    )
                )
            )
            when (result) {
                is ApiResult.Failure -> return if (songs.isEmpty()) result else ApiResult.Success(songs)
                is ApiResult.Success -> {
                    val arr = result.data.optJSONObject("searchResult3")?.optJSONArray("song") ?: JSONArray()
                    if (arr.length() == 0) break
                    songs.addAll(parseSongsArray(arr))
                    offset += arr.length()
                    if (arr.length() < pageSize) break
                }
            }
        }
        return ApiResult.Success(songs)
    }

    suspend fun star(config: ServerConfig, songId: String): ApiResult<Unit> {
        val result = getJson(buildUrl(config, "star", mapOf("id" to songId)))
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }

    suspend fun unstar(config: ServerConfig, songId: String): ApiResult<Unit> {
        val result = getJson(buildUrl(config, "unstar", mapOf("id" to songId)))
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }
}
