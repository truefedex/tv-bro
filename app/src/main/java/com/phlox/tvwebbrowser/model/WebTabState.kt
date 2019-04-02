package com.phlox.tvwebbrowser.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.webkit.WebChromeClient
import com.phlox.tvwebbrowser.activity.main.MainActivity

import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.utils.Utils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList

/**
 * Created by PDT on 24.08.2016.
 *
 * Class to store state of tab with webView
 */
class WebTabState {
    companion object {
        const val TAB_THUMBNAILS_DIR = "tabthumbs"
        const val FAVICONS_DIR = "favicons"

        @Synchronized
        fun saveTabs(context: Context, tabsStates: ArrayList<WebTabState>) {
            val store = JSONObject()
            val tabsStore = JSONArray()
            for (tab in tabsStates) {
                val tabJson = tab.toJson(context, true)
                tabsStore.put(tabJson)
            }
            try {
                store.put("tabs", tabsStore)
                val fos = context.openFileOutput(MainActivity.STATE_JSON, Context.MODE_PRIVATE)
                try {
                    fos.write(store.toString().toByteArray())
                } finally {
                    fos.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    var webView: WebViewEx? = null
    var savedState: Bundle? = null
    var currentOriginalUrl: String? = null
    var currentTitle: String? = null
    var selected: Boolean = false
    var thumbnail: Bitmap? = null
    var thumbnailHash: String? = null
    var favicon: Bitmap? = null
    var faviconHash: String? = null
    var webPageInteractionDetected = false
    var webChromeClient: WebChromeClient? = null

    constructor()

    constructor(context: Context, json: JSONObject) {
        try {
            currentOriginalUrl = json.getString("url")
            currentTitle = json.getString("title")
            selected = json.getBoolean("selected")
            if (json.has("thumbnail")) {
                thumbnailHash = json.getString("thumbnail")
                val thumbnailFile = File(context.cacheDir.absolutePath +
                        File.separator + WebTabState.TAB_THUMBNAILS_DIR +
                        File.separator + thumbnailHash)
                if (thumbnailFile.exists()) {
                    thumbnail = BitmapFactory.decodeFile(thumbnailFile.absolutePath)
                }
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

    fun toJson(context: Context, storeFiles: Boolean): JSONObject {
        val store = JSONObject()
        try {
            store.put("url", currentOriginalUrl)
            store.put("title", currentTitle)
            store.put("selected", selected)
            if (storeFiles) {
                val tabsThumbsDir = File(context.cacheDir.absolutePath + File.separator + WebTabState.TAB_THUMBNAILS_DIR)
                if (tabsThumbsDir.exists() || tabsThumbsDir.mkdir()) {
                    if (thumbnail != null) {
                        try {
                            val baos = ByteArrayOutputStream()
                            thumbnail!!.compress(Bitmap.CompressFormat.PNG, 100, baos)
                            val bitmapBytes = baos.toByteArray()
                            val hash = Utils.MD5_Hash(bitmapBytes)
                            if (hash != null && hash != thumbnailHash) {
                                if (thumbnailHash != null) {
                                    removeThumbnailFile(context)
                                    thumbnailHash = null
                                }
                                val file = File(tabsThumbsDir.absolutePath + File.separator + hash)
                                var fos: FileOutputStream? = null
                                try {
                                    fos = FileOutputStream(file)
                                    fos.write(bitmapBytes)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    fos?.close()
                                }

                                thumbnailHash = hash
                                store.put("thumbnail", thumbnailHash)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                if (faviconHash != null) {
                    store.put("favicon", faviconHash)
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        return store
    }

    fun removeFiles(context: Context) {
        if (thumbnailHash != null) {
            removeThumbnailFile(context)
        }
    }

    private fun removeThumbnailFile(context: Context) {
        val thumbnailFile = File(context.cacheDir.absolutePath +
                File.separator + WebTabState.TAB_THUMBNAILS_DIR +
                File.separator + thumbnailHash)
        thumbnailFile.delete()
    }

    fun restoreWebView() {
        if (savedState != null) {
            webView!!.restoreState(savedState)
        } else if (currentOriginalUrl != null) {
            webView!!.loadUrl(currentOriginalUrl)
        }
    }

    fun recycleWebView() {
        if (webView != null) {
            savedState = Bundle()
            webView!!.saveState(savedState)
            webView = null
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
}
