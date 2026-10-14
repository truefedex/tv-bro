package com.phlox.tvwebbrowser.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset


/**
 * Created by PDT on 24.08.2016.
 *
 * Class to store state of tab with webView
 */
@Entity(tableName = "tabs")
data class WebTabState(@PrimaryKey(autoGenerate = true)
                       var id: Long = 0,
                       var url: String = "",
                       var title: String = "",
                       var selected: Boolean = false,
                       var thumbnailHash: String? = null,
                       var faviconHash: String? = null,
                       var incognito: Boolean = false,
                       var position: Int = 0,
                       @ColumnInfo(name = "wv_state_file")
                       var wvStateFileName: String? = null,
                       var adblock: Boolean? = null,
                       var scale: Float? = null) {
    companion object {
        val TAG: String = WebTabState::class.java.simpleName

        const val TAB_THUMBNAILS_DIR = "tabthumbs"
        const val TAB_WVSTATES_DIR = "wvstates"

        /** Prefix of a saved state file name: the engine that saved it (see [WebEngine.stateFilePrefix]) */
        private fun stateFilePrefix(fileName: String): String {
            val index = fileName.indexOf(':')
            return if (index == -1) "" else fileName.substring(0, index + 1)
        }
    }

    @Ignore
    var thumbnail: Bitmap? = null
    @Ignore
    var savedState: Any? = null
    @Ignore
    private val webEngineHolder = lazy { WebEngineFactory.createWebEngine(this) }
    /** Created on the first access (usually when the tab is shown for the first time) */
    val webEngine: WebEngine get() = webEngineHolder.value
    val isWebEngineCreated: Boolean get() = webEngineHolder.isInitialized()
    @Ignore
    var lastLoadingUrl: String? = null //this is last url appeared in WebViewClient.shouldOverrideUrlLoading callback
    @Ignore
    var blockedAds = 0
    @Ignore
    var blockedPopups = 0
    @Ignore
    var cachedHostConfig: HostConfig? = null
    /** The page has a certificate error; cleared when the user navigates somewhere */
    @Ignore
    var certificateError = false

    private suspend fun saveThumbnail(context: Context) {
        val thumbnail = this.thumbnail
        val thumbnailHash = this.thumbnailHash
        val url = url
        if (thumbnail == null) return
        withContext(Dispatchers.IO) {
            synchronized(this@WebTabState) {
                val tabsThumbsDir = File(context.cacheDir.absolutePath + File.separator + TAB_THUMBNAILS_DIR)
                if (tabsThumbsDir.exists() || tabsThumbsDir.mkdir()) {
                    try {
                        val hash = Utils.hashMD5(url.toByteArray(Charset.defaultCharset()))
                        if (hash != null && hash != thumbnailHash) {
                            if (thumbnailHash != null) {
                                removeThumbnailFile()
                            }
                            val file = File(getThumbnailPath(hash))
                            var fos: FileOutputStream? = null
                            try {
                                fos = FileOutputStream(file)
                                thumbnail.compress(Bitmap.CompressFormat.PNG, 100, fos)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                fos?.close()
                            }

                            this@WebTabState.thumbnailHash = hash
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun getThumbnailPath(hash: String) =
        AppContext.get().cacheDir.absolutePath + File.separator + TAB_THUMBNAILS_DIR + File.separator + hash + ".png"

    private fun getWVStatePath(fileName: String): String {
        val hash = fileName.substring(stateFilePrefix(fileName).length)
        return AppContext.get().filesDir.absolutePath + File.separator + TAB_WVSTATES_DIR + File.separator + hash
    }

    fun removeFiles() {
        if (thumbnailHash != null) {
            removeThumbnailFile()
        }
        wvStateFileName?.apply {
            File(getWVStatePath(this)).delete()
            wvStateFileName = null
        }
    }

    private fun removeThumbnailFile() {
        if (thumbnailHash == null) return
        val thumbnailFile = File(getThumbnailPath(thumbnailHash!!))
        thumbnailFile.delete()
        thumbnailHash = null
    }

    fun restoreWebView(): Boolean {
        var state = savedState
        val stateFileName = wvStateFileName
        if (state != null) {
            webEngine.restoreState(state)
            return true
        } else if (stateFileName != null) {
            if (stateFilePrefix(stateFileName) != webEngine.stateFilePrefix) {
                //saved by another engine
                return false
            }
            try {
                val stateBytes = File(getWVStatePath(stateFileName)).readBytes()
                state = webEngine.stateFromBytes(stateBytes)
                if (state == null) return false
                this.savedState = state
                webEngine.restoreState(state)
                return true
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }
        return false
    }

    fun saveWebViewStateToFile() {
        val state = savedState
        var stateFileName = wvStateFileName
        if (stateFileName != null && stateFilePrefix(stateFileName) != webEngine.stateFilePrefix) {
            //saved by another engine
            File(getWVStatePath(stateFileName)).delete()
            stateFileName = null
        }
        if (state == null) return
        val stateBytes = when (state) {
            is Bundle -> {
                Utils.bundleToBytes(state) ?: return
            }
            else -> {
                state.toString().toByteArray(Charsets.UTF_8)
            }
        }
        if (stateFileName == null) {
            stateFileName = webEngine.stateFilePrefix + (Utils.hashMD5(stateBytes) ?: return)
        }
        try {
            val statesDir = File(AppContext.get().filesDir.absolutePath + File.separator + TAB_WVSTATES_DIR)
            if (statesDir.exists() || statesDir.mkdir()) {
                File(getWVStatePath(stateFileName)).writeBytes(stateBytes)
                wvStateFileName = stateFileName
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun trimMemory() {
        webEngine.trimMemory()
        savedState = null
    }

    fun onPause() {
        webEngine.let {
            savedState = it.saveState()
        }
    }

    suspend fun updateThumbnail(context: Context, thumbnail: Bitmap) {
        this.thumbnail = thumbnail
        val url = url
        var hash = Utils.hashMD5(url.toByteArray(Charset.defaultCharset()))
        if (hash != null) {
            hash += hashCode()//to make thumbnails from different tabs unique even with same url
            if (hash != thumbnailHash) {
                saveThumbnail(context)
            }
        }
    }

    fun loadThumbnail(): Bitmap? {
        val hash = thumbnailHash ?: return null
        val thumbnailFile = File(getThumbnailPath(hash))
        if (thumbnailFile.exists()) {
            thumbnail = BitmapFactory.decodeFile(thumbnailFile.absolutePath,
                    BitmapFactory.Options().apply { this.inMutable = true })
            return thumbnail
        } else {
            thumbnailHash = null
        }
        return null
    }
}
