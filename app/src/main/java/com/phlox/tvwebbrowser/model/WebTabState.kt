package com.phlox.tvwebbrowser.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.http.SslError
import android.os.Bundle
import android.webkit.WebChromeClient
import com.phlox.tvwebbrowser.TVBro

import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

/**
 * Created by PDT on 24.08.2016.
 *
 * Class to store state of tab with webView
 */
data class WebTabState(var currentOriginalUrl: String? = null, var currentTitle: String = "",
                       var selected: Boolean = false, var thumbnailHash: String? = null,
                       var faviconHash: String? = null, var thumbnail: Bitmap? = null,
                       var favicon: Bitmap? = null) {
    companion object {
        const val TAB_THUMBNAILS_DIR = "tabthumbs"
        const val FAVICONS_DIR = "favicons"
    }

    //fields that don't need to be persisted to json
    var webView: WebViewEx? = null
    var savedState: Bundle? = null
    var webPageInteractionDetected = false
    var webChromeClient: WebChromeClient? = null
    var lastSSLError: SslError? = null
    var trustSsl: Boolean = false
    var lastLoadingUrl: String? = null //this is last url appeared in WebViewClient.shouldOverrideUrlLoading callback

    constructor(context: Context, json: JSONObject) : this() {
        try {
            currentOriginalUrl = json.getString("url")
            currentTitle = json.getString("title")
            selected = json.getBoolean("selected")
            if (json.has("thumbnail")) {
                thumbnailHash = json.getString("thumbnail")
            }
            if (json.has("favicon")) {
                faviconHash = json.getString("favicon")
                val faviconFile = File(context.cacheDir.absolutePath +
                        File.separator + WebTabState.FAVICONS_DIR +
                        File.separator + faviconHash)
                if (faviconFile.exists()) {
                    favicon = BitmapFactory.decodeFile(faviconFile.absolutePath)
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }

    }

    fun toJson(): JSONObject {
        val store = JSONObject()
        try {
            store.put("url", currentOriginalUrl)
            store.put("title", currentTitle)
            store.put("selected", selected)
            if (thumbnailHash != null) {
                store.put("thumbnail", thumbnailHash)
            }
            if (faviconHash != null) {
                store.put("favicon", faviconHash)
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        return store
    }

    private fun saveThumbnail(context: Context, scope: CoroutineScope) {
        val thumbnail = this.thumbnail
        val thumbnailHash = this.thumbnailHash
        val url = currentOriginalUrl
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

    fun removeFiles() {
        if (thumbnailHash != null) {
            removeThumbnailFile()
        }
    }

    private fun removeThumbnailFile() {
        if (thumbnailHash == null) return
        val thumbnailFile = File(getThumbnailPath(thumbnailHash!!))
        thumbnailFile.delete()
        thumbnailHash = null
    }

    fun restoreWebView() {
        if (savedState != null) {
            webView?.restoreState(savedState)
        } else currentOriginalUrl?.apply { webView?.loadUrl(this) }
    }

    fun recycleWebView() {
        savedState = Bundle()
        webView?.saveState(savedState)
        webView = null
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
        val url = currentOriginalUrl
        if (url != null) {
            var hash = Utils.MD5_Hash(url.toByteArray(Charset.defaultCharset()))
            if (hash != null) {
                hash += hashCode()//to make thumbnails from different tabs unique even with same url
                if (hash != thumbnailHash) {
                    saveThumbnail(context, scope)
                }
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
