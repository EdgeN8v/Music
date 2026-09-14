package com.example.music.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "settings")

data class ServerConfig(
    val url: String = "",
    val username: String = "",
    val password: String = ""
) {
    val isConfigured get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/** On-disk streamed-audio cache settings (see AudioCache). Takes effect on next app start. */
data class CacheSettings(val enabled: Boolean = true, val limitMb: Int = 10_000)

/**
 * AUTO: use a USB drive if one's plugged in and granted, otherwise network.
 * NETWORK / USB: pin it regardless of what's plugged in.
 */
enum class MusicMode { AUTO, NETWORK, USB }

/**
 * Persists the Navidrome/Subsonic connection details.
 *
 * NOTE: DataStore Preferences stores this in plain text in app-private
 * storage. That's fine for a personal single-user app on your own phone,
 * but don't reuse this pattern if the password matters beyond that.
 */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val CACHE_LIMIT_MB = intPreferencesKey("cache_limit_mb")
        val CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val MUSIC_MODE = stringPreferencesKey("music_mode")
        val USB_TREE_URI = stringPreferencesKey("usb_tree_uri")
        val LOCAL_FAVORITE_IDS = stringSetPreferencesKey("local_favorite_ids")
        val MOOD_LABELS = stringPreferencesKey("mood_labels")
        val PLAYBACK_STATE = stringPreferencesKey("playback_state")
    }

    val config: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
        ServerConfig(
            url = prefs[Keys.URL] ?: "",
            username = prefs[Keys.USERNAME] ?: "",
            password = prefs[Keys.PASSWORD] ?: ""
        )
    }

    suspend fun save(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.URL] = config.url.trim().trimEnd('/')
            prefs[Keys.USERNAME] = config.username.trim()
            prefs[Keys.PASSWORD] = config.password
        }
    }

    val cacheSettings: Flow<CacheSettings> = context.dataStore.data.map { prefs ->
        CacheSettings(
            enabled = prefs[Keys.CACHE_ENABLED] ?: true,
            limitMb = prefs[Keys.CACHE_LIMIT_MB] ?: 10_000
        )
    }

    suspend fun saveCacheLimitMb(limitMb: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.CACHE_LIMIT_MB] = limitMb }
    }

    suspend fun saveCacheEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.CACHE_ENABLED] = enabled }
    }

    val musicMode: Flow<MusicMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.MUSIC_MODE]?.let { raw ->
            try { MusicMode.valueOf(raw) } catch (e: IllegalArgumentException) { MusicMode.AUTO }
        } ?: MusicMode.AUTO
    }

    suspend fun saveMusicMode(mode: MusicMode) {
        context.dataStore.edit { prefs -> prefs[Keys.MUSIC_MODE] = mode.name }
    }

    /** The SAF tree URI the user granted access to for the USB drive, as a string (null if never granted). */
    val usbTreeUri: Flow<String?> = context.dataStore.data.map { prefs -> prefs[Keys.USB_TREE_URI] }

    suspend fun saveUsbTreeUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri != null) prefs[Keys.USB_TREE_URI] = uri else prefs.remove(Keys.USB_TREE_URI)
        }
    }

    /** Favorite song ids for USB-sourced songs — there's no server to star them on, so we track it ourselves. */
    val localFavoriteIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.LOCAL_FAVORITE_IDS] ?: emptySet()
    }

    suspend fun toggleLocalFavorite(songId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.LOCAL_FAVORITE_IDS] ?: emptySet()
            prefs[Keys.LOCAL_FAVORITE_IDS] = if (songId in current) current - songId else current + songId
        }
    }

    /**
     * The single source of truth for every song's Energetic/Calm status —
     * NOT the file's own genre tag, which the app no longer reads for mood
     * at all (see SongRepository.applyMoodLabels). Keyed by "titleartist"
     * rather than a file path or a Navidrome song id, so it needs no server
     * round-trip to build or apply, and — per the point of using a stable
     * textual key instead of a path/id — a song that's genuinely replaced
     * (retitled, or swapped for different content under the same file) just
     * silently falls back to "no label" instead of inheriting a stale one,
     * since its key no longer matches anything in this map. Value is
     * "Energetic" or "Calm"; there's no "explicitly cleared" entry — clearing
     * a label just removes its key.
     *
     * Populated two ways: in bulk from tools/export_mood_labels.py's output
     * (import it once via Settings after running the PC-side classifier —
     * see that script's docstring), and incrementally from the phone itself
     * whenever you set or clear a song's mood in Library (long-press a row) —
     * both go through [setMoodLabel] / [clearMoodLabel], so there's exactly
     * one place this data lives and both write paths update the same file.
     */
    val moodLabels: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.MOOD_LABELS]?.let { raw ->
            try {
                val obj = JSONObject(raw)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } catch (e: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
    }

    suspend fun setMoodLabel(key: String, mood: String) {
        context.dataStore.edit { prefs ->
            val obj = prefs[Keys.MOOD_LABELS]?.let {
                try { JSONObject(it) } catch (e: Exception) { JSONObject() }
            } ?: JSONObject()
            obj.put(key, mood)
            prefs[Keys.MOOD_LABELS] = obj.toString()
        }
    }

    suspend fun clearMoodLabel(key: String) {
        context.dataStore.edit { prefs ->
            val raw = prefs[Keys.MOOD_LABELS] ?: return@edit
            val obj = try { JSONObject(raw) } catch (e: Exception) { return@edit }
            obj.remove(key)
            prefs[Keys.MOOD_LABELS] = obj.toString()
        }
    }

    /**
     * This file only ever lives on the one phone that wrote it, so losing or
     * switching phones would otherwise mean re-marking every song from
     * scratch. Export/import moves the raw JSON blob through a file the user
     * picks themselves (Settings screen) — anywhere a share sheet or
     * document picker can reach: Google Drive, email, or straight onto their
     * NAS if its app exposes a document provider. The same JSON shape is
     * also what tools/export_mood_labels.py produces for the initial bulk
     * import.
     */
    suspend fun exportMoodLabelsJson(): String {
        val obj = JSONObject()
        moodLabels.first().forEach { (key, mood) -> obj.put(key, mood) }
        return obj.toString()
    }

    /** Throws if [json] isn't valid JSON — let the caller show that as an import error instead of silently wiping existing labels. */
    suspend fun importMoodLabelsJson(json: String) {
        JSONObject(json) // validate before touching anything persisted
        context.dataStore.edit { prefs -> prefs[Keys.MOOD_LABELS] = json }
    }

    /**
     * What was playing, where in it, and what queue it was part of — so
     * relaunching the app can pick back up instead of leaving you to
     * re-find the song and start over from 0:00. Song ids only (not full
     * [com.example.music.data.Song] objects): the queue gets rebuilt by
     * matching these against whatever the freshly (re)loaded library
     * contains, so a song removed from the library since just silently
     * drops out of the restored queue instead of restoring stale data.
     */
    data class PlaybackState(
        val queueSongIds: List<String>,
        val currentIndex: Int,
        val positionMs: Long,
        val shuffled: Boolean,
        val moodTag: String?
    )

    val playbackState: Flow<PlaybackState?> = context.dataStore.data.map { prefs ->
        prefs[Keys.PLAYBACK_STATE]?.let { raw ->
            try {
                val obj = JSONObject(raw)
                val idsArray = obj.getJSONArray("queueSongIds")
                val ids = (0 until idsArray.length()).map { idsArray.getString(it) }
                PlaybackState(
                    queueSongIds = ids,
                    currentIndex = obj.getInt("currentIndex"),
                    positionMs = obj.getLong("positionMs"),
                    shuffled = obj.optBoolean("shuffled", false),
                    moodTag = obj.optString("moodTag", "").ifBlank { null }
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun savePlaybackState(state: PlaybackState) {
        context.dataStore.edit { prefs ->
            val obj = JSONObject()
            obj.put("queueSongIds", JSONArray(state.queueSongIds))
            obj.put("currentIndex", state.currentIndex)
            obj.put("positionMs", state.positionMs)
            obj.put("shuffled", state.shuffled)
            obj.put("moodTag", state.moodTag ?: "")
            prefs[Keys.PLAYBACK_STATE] = obj.toString()
        }
    }
}
