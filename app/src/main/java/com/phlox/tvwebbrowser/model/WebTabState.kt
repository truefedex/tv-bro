package com.phlox.tvwebbrowser.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.TextUtils
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.utils.LogUtils
import com.phlox.tvwebbrowser.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
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
                       var popupblock: Boolean? = null,
                       var scale: Float? = null) {
    companion object {
        const val TAB_THUMBNAILS_DIR = "tabthumbs"
        const val TAB_WVSTATES_DIR = "wvstates"
        const val FAVICONS_DIR = "favicons"
    }

    @Ignore
    var changingScale: Boolean = false

    @Ignore
    var thumbnail: Bitmap? = null
    @Ignore
    var favicon: Bitmap? = null
    @Ignore
    var savedState: Bundle? = null
    @Ignore
    var webView: WebViewEx? = null
    @Ignore
    var webPageInteractionDetected = false
    @Ignore
    var lastLoadingUrl: String? = null //this is last url appeared in WebViewClient.shouldOverrideUrlLoading callback
    @Ignore
    var hasAutoOpenedWindows = false

    constructor(context: Context, json: JSONObject) : this() {
        try {
            url = json.getString("url")
            title = json.getString("title")
            selected = json.getBoolean("selected")
            if (json.has("thumbnail")) {
                thumbnailHash = json.getString("thumbnail")
            }
            if (json.has("favicon")) {
                faviconHash = json.getString("favicon")
                loadFavicon(context)
            }
            if (json.has("wv_state")) {
                val state = Utils.convertJsonToBundle(json.getJSONObject("wv_state"))
                if (state != null && !state.isEmpty) {
                    savedState = state
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
            LogUtils.recordException(e)
        }

    }

    fun loadFavicon(context: Context) {
        val faviconFile = File(
          context.cacheDir.absolutePath +
            File.separator + FAVICONS_DIR +
            File.separator + faviconHash
        )
        if (faviconFile.exists()) {
            favicon = BitmapFactory.decodeFile(faviconFile.absolutePath)
        }
    }

    private fun saveThumbnail(context: Context, scope: CoroutineScope) {
        val thumbnail = this.thumbnail
        val thumbnailHash = this.thumbnailHash
        val url = url
        if (thumbnail == null || url == null) return
        scope.launch(Dispatchers.IO) {
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
        TVBro.instance.cacheDir.absolutePath + File.separator + TAB_THUMBNAILS_DIR + File.separator + hash + ".png"

    private fun getWVStatePath(hash: String) =
            TVBro.instance.filesDir.absolutePath + File.separator + TAB_WVSTATES_DIR + File.separator + hash

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

    fun restoreWebView() {
        val restored = kotlin.run {
            var state = savedState
            val stateFileName = wvStateFileName
            if (state != null) {
                webView?.restoreState(state)
                return@run true
            } else if (stateFileName != null) {
                try {
                    val stateBytes = File(getWVStatePath(stateFileName)).readBytes()
                    state = Utils.bytesToBundle(stateBytes)
                    if (state == null) return@run false
                    webView?.restoreState(state)
                    return@run true
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@run false
                }
            }
            return@run false
        }
        if (!restored) {
            this.url.takeIf { !TextUtils.isEmpty(url) }?.apply { webView?.loadUrl(this) }
        }
    }

    fun saveWebViewStateToFile() {
        val state = savedState
        var stateFileName = wvStateFileName
        if (state == null) return
        val stateBytes = Utils.bundleToBytes(state) ?: return
        if (stateFileName == null) {
            stateFileName = Utils.MD5_Hash(stateBytes) ?: return
        }
        try {
            val statesDir = File(TVBro.instance.filesDir.absolutePath + File.separator + TAB_WVSTATES_DIR)
            if (statesDir.exists() || statesDir.mkdir()) {
                File(getWVStatePath(stateFileName)).writeBytes(stateBytes)
                wvStateFileName = stateFileName
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun recycleWebView() {
        webView = null
        savedState = null
    }

    fun onPause() {
        webView?.apply {
            val state = Bundle()
            saveState(state)
            savedState = state
        }
    }

    fun updateFavIcon(context: Context, icon: Bitmap?) {
        favicon = icon
        if (favicon == null) {
            faviconHash = null
            return
        }
        val favIconsDir = File(context.cacheDir.absolutePath + File.separator + WebTabState.FAVICONS_DIR)
        if (favIconsDir.exists() || favIconsDir.mkdir()) {
            var bitmapBytes: ByteArray? = null
            var hash: String? = null
            try {
                val baos = ByteArrayOutputStream()
                icon!!.compress(Bitmap.CompressFormat.PNG, 100, baos)
                bitmapBytes = baos.toByteArray()
                hash = Utils.MD5_Hash(bitmapBytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (bitmapBytes == null || hash == null || hash == faviconHash) return

            faviconHash = hash

            val file = File(favIconsDir.absolutePath + File.separator + faviconHash)
            if (!file.exists()) {
                var fos: FileOutputStream? = null
                try {
                    fos = FileOutputStream(file)
                    fos.write(bitmapBytes)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    fos?.close()
                }
            }
        }
    }

    fun updateThumbnail(context: Context, thumbnail: Bitmap, scope: CoroutineScope) {
        this.thumbnail = thumbnail
        val url = url
        var hash = Utils.MD5_Hash(url.toByteArray(Charset.defaultCharset()))
        if (hash != null) {
            hash += hashCode()//to make thumbnails from different tabs unique even with same url
            if (hash != thumbnailHash) {
                saveThumbnail(context, scope)
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
