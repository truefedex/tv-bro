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
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.isGecko
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
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
                       @Deprecated("This field is not used anymore")
                       @ColumnInfo(name = "wv_state", typeAffinity = ColumnInfo.BLOB)
                       var wvState: ByteArray? = null,
                       @ColumnInfo(name = "wv_state_file")
                       var wvStateFileName: String? = null,
                       var adblock: Boolean? = null,
                       var scale: Float? = null) {
    companion object {
        val TAG: String = WebTabState::class.java.simpleName

        const val TAB_THUMBNAILS_DIR = "tabthumbs"
        const val TAB_WVSTATES_DIR = "wvstates"
        const val GECKO_SESSION_STATE_HASH_PREFIX = "gecko:"
    }

    @Ignore
    var thumbnail: Bitmap? = null
    @Ignore
    var savedState: Any? = null
    @delegate:Ignore
    val webEngine by lazy { WebEngineFactory.createWebEngine(this) }
    @Ignore
    var lastLoadingUrl: String? = null //this is last url appeared in WebViewClient.shouldOverrideUrlLoading callback
    @Ignore
    var blockedAds = 0
    @Ignore
    var blockedPopups = 0
    @Ignore
    var cachedHostConfig: HostConfig? = null

    constructor(context: Context, json: JSONObject) : this() {
        try {
            url = json.getString("url")
            title = json.getString("title")
            selected = json.getBoolean("selected")
            if (json.has("thumbnail")) {
                thumbnailHash = json.getString("thumbnail")
            }
            if (json.has("wv_state")) {
                val state = Utils.convertJsonToBundle(json.getJSONObject("wv_state"))
                if (state != null && !state.isEmpty) {
                    savedState = state
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }

    }

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
                        val hash = Utils.MD5_Hash(url.toByteArray(Charset.defaultCharset()))
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

    private fun getWVStatePath(hash: String): String {
        return if (hash.startsWith(GECKO_SESSION_STATE_HASH_PREFIX)) {
            AppContext.get().filesDir.absolutePath + File.separator + TAB_WVSTATES_DIR + File.separator + hash.substring(
                GECKO_SESSION_STATE_HASH_PREFIX.length)
        } else {
            AppContext.get().filesDir.absolutePath + File.separator + TAB_WVSTATES_DIR + File.separator + hash
        }
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
            if (stateFileName.startsWith(GECKO_SESSION_STATE_HASH_PREFIX) xor webEngine.isGecko()) {
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
        if (stateFileName != null && (
                    (webEngine.isGecko() && !stateFileName.startsWith(
                        GECKO_SESSION_STATE_HASH_PREFIX
                    )) ||
                            ((!webEngine.isGecko()) && stateFileName.startsWith(
                                GECKO_SESSION_STATE_HASH_PREFIX
                            ))
                    )) {
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
            stateFileName = Utils.MD5_Hash(stateBytes) ?: return
            if (webEngine.isGecko()) {
                stateFileName = GECKO_SESSION_STATE_HASH_PREFIX + stateFileName
            }
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
        var hash = Utils.MD5_Hash(url.toByteArray(Charset.defaultCharset()))
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
