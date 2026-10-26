package com.phlox.tvwebbrowser.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.WebEngine
import java.io.File
import java.util.UUID

/**
 * Files of tabs: saved states of their engines and thumbnails. A tab keeps only the file names
 * ([BrowserTab.wvStateFileName], [BrowserTab.thumbnailHash]); the blocking writes here get
 * and return the names, so they can run in the background without touching the tab.
 */
class TabFilesStore(filesDir: File, cacheDir: File) {
    companion object {
        private const val TAG = "TabFilesStore"
        const val ENGINE_STATES_DIR = "wvstates"
        const val THUMBNAILS_DIR = "tabthumbs"
        private const val THUMBNAIL_QUALITY = 80

        /** Prefix of a saved state file name: the engine that saved it (see [WebEngine.stateFilePrefix]) */
        private fun stateFilePrefix(fileName: String): String {
            val index = fileName.indexOf(':')
            return if (index == -1) "" else fileName.substring(0, index + 1)
        }
    }

    private val statesDir = File(filesDir, ENGINE_STATES_DIR)
    private val thumbnailsDir = File(cacheDir, THUMBNAILS_DIR)

    private fun stateFile(fileName: String) = File(statesDir, fileName.substring(stateFilePrefix(fileName).length))

    /** Thumbnails saved before had only a hash for a name and were PNG */
    private fun thumbnailFile(name: String) = File(thumbnailsDir, if (name.contains('.')) name else "$name.png")

    /** The file of a saved thumbnail ([BrowserTab.thumbnailHash]); null if it is not there (the cache was cleared) */
    fun existingThumbnailFile(name: String): File? = thumbnailFile(name).takeIf { it.isFile }

    /**
     * Restores the state of the tab's engine: the one kept in memory or the saved one.
     * @return false if there is nothing to restore (or it was saved by another engine)
     */
    fun restoreEngineState(tab: BrowserTab): Boolean {
        val webEngine = tab.webEngine
        tab.savedState?.let {
            webEngine.restoreState(it)
            return true
        }
        val stateFileName = tab.wvStateFileName ?: return false
        if (stateFilePrefix(stateFileName) != webEngine.stateFilePrefix) {
            //saved by another engine
            return false
        }
        return try {
            val state = webEngine.stateFromBytes(stateFile(stateFileName).readBytes()) ?: return false
            tab.savedState = state
            webEngine.restoreState(state)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Writes an engine state to a file (blocking).
     * @param enginePrefix [WebEngine.stateFilePrefix] of the tab's engine
     * @param oldFileName the file the tab has now
     * @return the file of the tab after the write
     */
    fun writeEngineState(state: Any?, enginePrefix: String, oldFileName: String?): String? {
        var stateFileName = oldFileName
        if (stateFileName != null && stateFilePrefix(stateFileName) != enginePrefix) {
            //saved by another engine
            stateFile(stateFileName).delete()
            stateFileName = null
        }
        if (state == null) return stateFileName
        val stateBytes = when (state) {
            is Bundle -> Utils.bundleToBytes(state) ?: return stateFileName
            else -> state.toString().toByteArray(Charsets.UTF_8)
        }
        val fileName = stateFileName ?: (enginePrefix + (Utils.hashMD5(stateBytes) ?: return null))
        return try {
            if (!statesDir.exists() && !statesDir.mkdirs()) return stateFileName
            stateFile(fileName).writeBytes(stateBytes)
            fileName
        } catch (e: Exception) {
            e.printStackTrace()
            stateFileName
        }
    }

    /**
     * Writes a thumbnail to a new file and deletes the previous one (blocking).
     * @return the name of the new file, or [oldName] if the thumbnail can not be written
     */
    fun writeThumbnail(thumbnail: Bitmap, oldName: String?): String? {
        if (!thumbnailsDir.exists() && !thumbnailsDir.mkdirs()) return oldName
        val name = UUID.randomUUID().toString() + ".webp"
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        try {
            thumbnailFile(name).outputStream().use { thumbnail.compress(format, THUMBNAIL_QUALITY, it) }
        } catch (e: Exception) {
            Log.e(TAG, "writeThumbnail", e)
            thumbnailFile(name).delete()
            return oldName
        }
        oldName?.let { thumbnailFile(it).delete() }
        return name
    }

    /**
     * Reads a saved thumbnail (blocking).
     * @return null if its file is lost
     */
    fun loadThumbnail(name: String): Bitmap? {
        val file = thumbnailFile(name)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inMutable = true })
    }

    /**
     * Deletes the files of a tab (blocking).
     */
    fun removeFiles(stateFileName: String?, thumbnailName: String?) {
        thumbnailName?.let { thumbnailFile(it).delete() }
        stateFileName?.let { stateFile(it).delete() }
    }
}
