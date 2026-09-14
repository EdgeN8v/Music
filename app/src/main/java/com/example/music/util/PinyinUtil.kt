package com.example.music.util

import android.icu.text.Transliterator
import com.example.music.data.Song
import java.text.Normalizer
import java.util.Locale

/**
 * Turns song titles into a stable A–Z ordering, contacts-app style: Chinese
 * characters are romanized to pinyin ("土坡上的狗为拔草" -> starts with "Tu...",
 * so it lands under "T"), English titles sort on their own first letter, and
 * both share the same bucket when they start with the same sound (e.g. "阿"
 * and "Apple" both land under "A").
 *
 * Uses android.icu.text.Transliterator (built into the Android framework
 * since API 24, so no extra pinyin dictionary dependency is needed) rather
 * than a fixed order from the server — the server's getRandomSongs endpoint
 * returns a different order on every call, which is what was causing the
 * "list re-shuffles every time I open Library" bug.
 */
object PinyinUtil {

    private val transliterator: Transliterator? by lazy {
        try {
            Transliterator.getInstance("Han-Latin")
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * ICU's Han-Latin transliterator picks one reading per character with no
     * word-level context, so 多音字 (characters with more than one reading)
     * often come out wrong — e.g. "重生" (rebirth, chóngshēng) transliterated
     * as "zhongsheng" because 重 defaults to its zhòng (heavy) reading, which
     * sorted it under Z instead of C. Word-level overrides checked before
     * ICU runs fix the specific words listed here; this is a small curated
     * list, not a general solution — add to it as more mismatches turn up.
     */
    private val polyphoneOverrides = listOf(
        "重生" to "Chongsheng",
        "重逢" to "Chongfeng",
        "重来" to "Chonglai",
        "重复" to "Chongfu",
        "重叠" to "Chongdie",
        "重新" to "Chongxin",
        "曾经" to "Cengjing"
    )

    /** Romanizes [text] and strips tone-mark diacritics, e.g. "土坡" -> "tu po". */
    private fun toLatin(text: String): String {
        var preprocessed = text
        for ((word, reading) in polyphoneOverrides) {
            preprocessed = preprocessed.replace(word, " $reading ")
        }
        val converted = try {
            transliterator?.transliterate(preprocessed) ?: preprocessed
        } catch (e: Throwable) {
            preprocessed
        }
        // Strip combining diacritical marks (tone marks) left over from Han-Latin,
        // e.g. "tǔ" -> "tu".
        return Normalizer.normalize(converted, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
    }

    /** Sort key used to order songs within the same letter bucket. */
    fun sortKey(title: String): String = toLatin(title).uppercase(Locale.ROOT)

    /** The single A–Z bucket letter this title belongs under, "#" if none applies. */
    fun indexLetter(title: String): String {
        val letter = sortKey(title).firstOrNull { it in 'A'..'Z' }
        return letter?.toString() ?: "#"
    }

    private fun letterRank(letter: String): Int =
        if (letter.length == 1 && letter[0] in 'A'..'Z') letter[0] - 'A' else 26

    /** A song paired with the index letter it was sorted under, so screens don't have to re-run [toLatin] just to draw sticky headers. */
    data class IndexedSong(val song: Song, val letter: String)

    /**
     * Sorts once, computing each song's Latin sort key via [toLatin] exactly
     * once per song (not per comparison — `sortedWith` re-invokes its key
     * selectors on every pairwise comparison, and Transliterator calls are
     * expensive enough that doing this naively over a few thousand songs is
     * what made Library/Home feel like they "reload" on every visit).
     */
    fun indexAndSort(songs: List<Song>): List<IndexedSong> =
        songs
            .map { song ->
                val key = sortKey(song.title)
                val letter = key.firstOrNull { it in 'A'..'Z' }?.toString() ?: "#"
                Triple(song, key, letter)
            }
            .sortedWith(compareBy({ letterRank(it.third) }, { it.second }, { it.first.title }))
            .map { (song, _, letter) -> IndexedSong(song, letter) }

    /** Stable alphabetical ordering (by pinyin/latin initial, then full key) for display and playback queues. */
    fun sortSongsAlphabetically(songs: List<Song>): List<Song> = indexAndSort(songs).map { it.song }
}
