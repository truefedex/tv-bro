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
    var webView: WebViewEx? = null
    var savedState: Bundle? = null
    var currentOriginalUrl: String? = null
    var currentTitle: String? = null
    var selected: Boolean = false
    var thumbnail: Bitmap? = null
    var thumbnailHash: String? = null
    var webPageInteractionDetected = false
    var webChromeClient: WebChromeClient? = null

    constructor() {}

    constructor(context: Context, json: JSONObject) {
        try {
            currentOriginalUrl = json.getString("url")
            currentTitle = json.getString("title")
            selected = json.getBoolean("selected")
            if (json.has("thumbnail")) {
                thumbnailHash = json.getString("thumbnail")
                val thumbnailFile = File(context.filesDir.absolutePath +
                        File.separator + WebTabState.BLOBS_DIR +
                        File.separator + thumbnailHash)
                if (thumbnailFile.exists()) {
                    thumbnail = BitmapFactory.decodeFile(thumbnailFile.absolutePath)
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
                val tabsBlobsDir = File(context.filesDir.absolutePath + File.separator + WebTabState.BLOBS_DIR)
                if (tabsBlobsDir.exists() || tabsBlobsDir.mkdir()) {
                    if (thumbnail != null) {
                        if (thumbnailHash != null) {
                            removeThumbnailFile(context)
                            thumbnailHash = null
                        }
                        val baos = ByteArrayOutputStream()
                        thumbnail!!.compress(Bitmap.CompressFormat.PNG, 100, baos) //bm is the bitmap object
                        val bitmapBytes = baos.toByteArray()
                        val hash = Utils.MD5_Hash(bitmapBytes)
                        if (hash != null) {
                            val file = File(tabsBlobsDir.absolutePath + File.separator + hash)
                            try {
                                val fis = FileOutputStream(file)
                                fis.write(bitmapBytes)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            thumbnailHash = hash
                            store.put("thumbnail", thumbnailHash)
                        }
                    }
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
        val thumbnailFile = File(context.filesDir.absolutePath +
                File.separator + WebTabState.BLOBS_DIR +
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

    companion object {
        val BLOBS_DIR = "tabs_blobs"

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
}
