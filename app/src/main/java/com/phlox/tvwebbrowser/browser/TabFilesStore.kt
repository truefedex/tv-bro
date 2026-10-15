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
 * ([BrowserTab.wvStateFileName], [BrowserTab.thumbnailHash]).
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
     * Writes the engine state kept in the tab to a file (blocking).
     */
    fun saveEngineState(tab: BrowserTab) {
        val state = tab.savedState
        var stateFileName = tab.wvStateFileName
        if (stateFileName != null && stateFilePrefix(stateFileName) != tab.webEngine.stateFilePrefix) {
            //saved by another engine
            stateFile(stateFileName).delete()
            stateFileName = null
            tab.wvStateFileName = null
        }
        if (state == null) return
        val stateBytes = when (state) {
            is Bundle -> Utils.bundleToBytes(state) ?: return
            else -> state.toString().toByteArray(Charsets.UTF_8)
        }
        if (stateFileName == null) {
            stateFileName = tab.webEngine.stateFilePrefix + (Utils.hashMD5(stateBytes) ?: return)
        }
        try {
            if (statesDir.exists() || statesDir.mkdirs()) {
                stateFile(stateFileName).writeBytes(stateBytes)
                tab.wvStateFileName = stateFileName
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Writes the thumbnail to a new file of the tab and deletes the previous one (blocking).
     */
    fun saveThumbnail(tab: BrowserTab, thumbnail: Bitmap) {
        if (!thumbnailsDir.exists() && !thumbnailsDir.mkdirs()) return
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
            Log.e(TAG, "saveThumbnail", e)
            thumbnailFile(name).delete()
            return
        }
        tab.thumbnailHash?.let { thumbnailFile(it).delete() }
        tab.thumbnailHash = name
    }

    /**
     * Reads the saved thumbnail of the tab (blocking).
     * @return null if there is none or its file is lost
     */
    fun loadThumbnail(tab: BrowserTab): Bitmap? {
        val name = tab.thumbnailHash ?: return null
        val file = thumbnailFile(name)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inMutable = true })
    }

    /**
     * Deletes all files of the tab (blocking).
     */
    fun removeFiles(tab: BrowserTab) {
        tab.thumbnailHash?.let {
            thumbnailFile(it).delete()
            tab.thumbnailHash = null
        }
        tab.wvStateFileName?.let {
            stateFile(it).delete()
            tab.wvStateFileName = null
        }
    }
}
