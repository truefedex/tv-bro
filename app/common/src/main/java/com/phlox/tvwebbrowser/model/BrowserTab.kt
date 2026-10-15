package com.phlox.tvwebbrowser.model

import android.graphics.Bitmap
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory


/**
 * Created by PDT on 24.08.2016.
 *
 * Class to store state of tab with webView.
 * Not a data class: a tab is identified by the object, not by its (changing) fields.
 */
@Entity(tableName = "tabs")
class BrowserTab(@PrimaryKey(autoGenerate = true)
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
        val TAG: String = BrowserTab::class.java.simpleName
    }

    /** The picture of the page; the file is written when the tab is saved */
    @Ignore
    var thumbnail: Bitmap? = null
    /** [thumbnail] was updated and is not saved yet */
    @Ignore
    private var thumbnailChanged = false
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

    fun trimMemory() {
        if (isWebEngineCreated) {
            webEngine.trimMemory()
        }
        savedState = null
    }

    fun onPause() {
        webEngine.let {
            savedState = it.saveState()
        }
    }

    fun updateThumbnail(thumbnail: Bitmap) {
        this.thumbnail = thumbnail
        thumbnailChanged = true
    }

    /**
     * @return the thumbnail to save if it was updated since the last call
     */
    fun takeChangedThumbnail(): Bitmap? {
        if (!thumbnailChanged) return null
        thumbnailChanged = false
        return thumbnail
    }
}
