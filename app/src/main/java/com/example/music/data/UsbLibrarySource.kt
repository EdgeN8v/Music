package com.example.music.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "wma", "ape", "opus")

/**
 * Reads a music library straight off a USB drive instead of a Subsonic
 * server — no network, no NAS, nothing to time out. Whole-drive scan (any
 * folder layout), classified the same way the network mode already is: off
 * each file's own ID3 tags (title/artist/genre), not folder placement, so
 * files tagged 激情/平静 by the same offline classification step work
 * identically whether they came from the NAS or a thumb drive.
 *
 * Android won't hand out raw filesystem access to removable storage past
 * API 29 (scoped storage), so this goes through the Storage Access
 * Framework: the user grants a folder tree once via the system picker, we
 * keep that permission (see SettingsRepository.usbTreeUri), and everything
 * after that is DocumentFile calls instead of java.io.File.
 */
object UsbLibrarySource {

    /** True if a removable, non-primary volume is currently mounted — i.e. some kind of USB/SD drive is plugged in. */
    fun isUsbPresent(context: Context): Boolean {
        val sm = context.getSystemService(StorageManager::class.java) ?: return false
        return sm.storageVolumes.any { it.isRemovable && !it.isPrimary }
    }

    /**
     * Intent to ask the user to grant access to the plugged-in drive's folder tree.
     * On API 29+ this is biased to open the system picker at the drive itself
     * (via StorageVolume.createOpenDocumentTreeIntent), so granting is just
     * "confirm this folder" instead of navigating storage manually.
     *
     * Must request FLAG_GRANT_PERSISTABLE_URI_PERMISSION here on the request
     * itself — the returned Uri only supports a persistable grant if this was
     * asked for up front. Without it, [takePersistableAccess] throws
     * SecurityException (this was the "app crashes right after granting USB
     * access" bug).
     */
    fun createAccessIntent(context: Context): Intent {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sm = context.getSystemService(StorageManager::class.java)
            val volume = sm?.storageVolumes?.firstOrNull { it.isRemovable && !it.isPrimary }
            volume?.createOpenDocumentTreeIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }
        return base.addFlags(flags)
    }

    /**
     * Persists read access to the granted tree and records it. Returns true on success —
     * best-effort, never throws, so a picker quirk on some device can't crash the app.
     *
     * takePersistableUriPermission's modeFlags only accepts
     * FLAG_GRANT_READ/WRITE_URI_PERMISSION — passing FLAG_GRANT_PERSISTABLE_URI_PERMISSION
     * here too (it belongs only on the original request Intent, see
     * createAccessIntent) throws IllegalArgumentException("Requested flags
     * 0x41, but only 0x3 are allowed"), which is the exact crash this was
     * hitting right after tapping Allow.
     */
    fun takePersistableAccess(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            MusicSource.onUsbAccessGranted(uri)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Recursively scans a previously-granted tree for audio files. Can take a while on a big drive — call off the main thread. */
    suspend fun scanLibrary(context: Context, treeUri: Uri): List<Song> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val songs = mutableListOf<Song>()
        val stack = ArrayDeque<DocumentFile>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in dir.listFiles()) {
                when {
                    child.isDirectory -> stack.addLast(child)
                    isAudioFile(child.name) -> songs.add(readSong(context, child))
                }
            }
        }
        songs
    }

    private fun isAudioFile(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in AUDIO_EXTENSIONS
    }

    private fun readSong(context: Context, file: DocumentFile): Song {
        var title = file.name?.substringBeforeLast('.') ?: "未知标题"
        var artist = ""
        var genre: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, file.uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.takeIf { it.isNotBlank() }?.let { genre = it }
        } catch (e: Exception) {
            // Unreadable tags — still playable, just falls back to the filename.
        } finally {
            // release(), not close(): close() only exists from API 29, release() since API 1.
            retriever.release()
        }
        return Song(
            id = file.uri.toString(),
            title = title,
            artist = artist,
            genre = genre,
            localUri = file.uri.toString()
        )
    }
}
