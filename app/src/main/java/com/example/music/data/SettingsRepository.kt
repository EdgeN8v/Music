package com.example.music.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "settings")

private const val MAX_SEARCH_HISTORY = 10

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
        val FAVORITES_FIRST = booleanPreferencesKey("favorites_first")
        val PLAY_MODE = stringPreferencesKey("play_mode")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val FAVORITE_KEYS = stringPreferencesKey("favorite_keys")
        val FAVORITES_SEEDED = booleanPreferencesKey("favorites_seeded")
    }

    // distinctUntilChanged: DataStore emits its whole snapshot whenever *any*
    // preference changes (e.g. the playback position that's saved every ~10s
    // while a song plays), and a plain .map re-emits an identical ServerConfig
    // each time. Settings collects this to fill its text fields, so every one
    // of those unrelated writes stomped whatever you were in the middle of
    // typing back to the saved value — "I delete the username and it types
    // itself back in".
    val config: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
        ServerConfig(
            url = prefs[Keys.URL] ?: "",
            username = prefs[Keys.USERNAME] ?: "",
            password = prefs[Keys.PASSWORD] ?: ""
        )
    }.distinctUntilChanged()

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

    /**
     * Raw 顺序/单曲循环/随机 mode name (SEQUENTIAL/REPEAT_ONE/SHUFFLE) so it
     * survives an app restart — without this, every cold start reset back to
     * SEQUENTIAL regardless of what you'd left it on, which made 激情/平静
     * always start from the same handful of songs. The PlayMode enum itself
     * lives in the playback package, not here, so this just persists
     * whatever string PlayerController hands it — no dependency the other way.
     */
    val playModeName: Flow<String?> = context.dataStore.data.map { prefs -> prefs[Keys.PLAY_MODE] }

    suspend fun savePlayModeName(name: String) {
        context.dataStore.edit { prefs -> prefs[Keys.PLAY_MODE] = name }
    }

    /** Recent search queries, newest first, capped at [MAX_SEARCH_HISTORY] — shown in the search overlay while the box is still empty. */
    val searchHistory: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.SEARCH_HISTORY]?.let { raw ->
            try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    /**
     * NonCancellable: this is called right as the search overlay closes
     * (picking a result dismisses it), which disposes the composable scope
     * the call was launched from — same cancel-mid-write trap as
     * SongRepository.setMoodLabel.
     */
    suspend fun addSearchHistory(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        withContext(NonCancellable) {
            context.dataStore.edit { prefs ->
                val current = prefs[Keys.SEARCH_HISTORY]?.let { raw ->
                    try {
                        val arr = JSONArray(raw)
                        (0 until arr.length()).map { arr.getString(it) }
                    } catch (e: Exception) {
                        emptyList()
                    }
                } ?: emptyList()
                val updated = (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_SEARCH_HISTORY)
                prefs[Keys.SEARCH_HISTORY] = JSONArray(updated).toString()
            }
        }
    }

    suspend fun clearSearchHistory() {
        context.dataStore.edit { prefs -> prefs.remove(Keys.SEARCH_HISTORY) }
    }

    /**
     * Whether 激情/平静 put favorited songs first (see HomeScreen.playMood).
     * Defaults on (matches the behavior before this was made a toggle) —
     * turned off when you want a fresh mix instead of the same favorited
     * handful every time.
     */
    val favoritesFirst: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[Keys.FAVORITES_FIRST] ?: true }

    suspend fun setFavoritesFirst(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.FAVORITES_FIRST] = enabled }
    }

    /** The SAF tree URI the user granted access to for the USB drive, as a string (null if never granted). */
    val usbTreeUri: Flow<String?> = context.dataStore.data.map { prefs -> prefs[Keys.USB_TREE_URI] }

    suspend fun saveUsbTreeUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri != null) prefs[Keys.USB_TREE_URI] = uri else prefs.remove(Keys.USB_TREE_URI)
        }
    }

    /** Legacy: USB favorites used to be tracked by song id. Only read now to migrate them into [favoriteKeys] — see [migrateLocalFavoriteIds]. */
    val localFavoriteIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.LOCAL_FAVORITE_IDS] ?: emptySet()
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
    /**
     * Favorites live in the same file as the mood labels, as
     * `{"version":2,"moods":{key:mood,…},"favorites":[key,…]}`. [importMoodLabelsJson]
     * still accepts the older flat `{key:mood}` shape (that's also what
     * tools/export_mood_labels.py writes) — it just leaves favorites alone.
     */
    suspend fun exportMoodLabelsJson(): String {
        val moods = JSONObject()
        moodLabels.first().forEach { (key, mood) -> moods.put(key, mood) }
        return JSONObject()
            .put("version", 2)
            .put("moods", moods)
            .put("favorites", JSONArray(favoriteKeys.first().toList()))
            .toString()
    }

    /** Throws if [json] isn't valid JSON — let the caller show that as an import error instead of silently wiping existing labels. */
    suspend fun importMoodLabelsJson(json: String) {
        val root = JSONObject(json) // validate before touching anything persisted
        val moods = root.optJSONObject("moods")
        context.dataStore.edit { prefs ->
            if (moods != null) {
                prefs[Keys.MOOD_LABELS] = moods.toString()
                root.optJSONArray("favorites")?.let {
                    prefs[Keys.FAVORITE_KEYS] = it.toString()
                    prefs[Keys.FAVORITES_SEEDED] = true
                }
            } else {
                prefs[Keys.MOOD_LABELS] = json
            }
        }
    }

    /**
     * Which songs are favorited, keyed by "title+artist" — the same key the
     * mood labels use, and for the same reason: it doesn't depend on a
     * Navidrome account, song id or file path. Favorites used to be Navidrome
     * "stars", which belong to a server *account* — switch username/URL, or
     * have the library rescanned under new ids after re-tagging, and every
     * favorite silently vanished. Now it's a local file you can export with
     * the mood labels, and the server isn't involved at all.
     */
    val favoriteKeys: Flow<Set<String>> = context.dataStore.data.map { prefs -> parseKeySet(prefs[Keys.FAVORITE_KEYS]) }

    val favoritesSeeded: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[Keys.FAVORITES_SEEDED] ?: false }

    private fun parseKeySet(raw: String?): Set<String> =
        raw?.let {
            try {
                val arr = JSONArray(it)
                (0 until arr.length()).mapTo(LinkedHashSet()) { i -> arr.getString(i) }
            } catch (e: Exception) {
                emptySet()
            }
        } ?: emptySet()

    suspend fun setFavorite(key: String, favorite: Boolean) {
        context.dataStore.edit { prefs ->
            val current = parseKeySet(prefs[Keys.FAVORITE_KEYS])
            prefs[Keys.FAVORITE_KEYS] = JSONArray((if (favorite) current + key else current - key).toList()).toString()
        }
    }

    /** Merges [keys] into the favorites (never removes any) and marks the one-time migration from server stars as done. */
    suspend fun seedFavorites(keys: Set<String>) {
        context.dataStore.edit { prefs ->
            val merged = parseKeySet(prefs[Keys.FAVORITE_KEYS]) + keys
            prefs[Keys.FAVORITE_KEYS] = JSONArray(merged.toList()).toString()
            prefs[Keys.FAVORITES_SEEDED] = true
        }
    }

    /** USB songs used to be favorited by id; folds those into the key-based set and drops the old id set. */
    suspend fun migrateLocalFavoriteIds(keysForIds: Set<String>) {
        context.dataStore.edit { prefs ->
            val merged = parseKeySet(prefs[Keys.FAVORITE_KEYS]) + keysForIds
            prefs[Keys.FAVORITE_KEYS] = JSONArray(merged.toList()).toString()
            prefs.remove(Keys.LOCAL_FAVORITE_IDS)
        }
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
