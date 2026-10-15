package com.phlox.tvwebbrowser.model

import android.graphics.Bitmap
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory


/**
 * A browser tab in memory: its engine, thumbnail and page state. [toEntity] gives what is kept in the database.
 * Not a data class: a tab is identified by the object, not by its (changing) fields.
 * Read and changed on the main thread only.
 */
class BrowserTab(var id: Long = 0,
                 var url: String = "",
                 var title: String = "",
                 var selected: Boolean = false,
                 var thumbnailHash: String? = null,
                 var faviconHash: String? = null,
                 var incognito: Boolean = false,
                 var position: Int = 0,
                 var wvStateFileName: String? = null,
                 var adblock: Boolean? = null,
                 var scale: Float? = null) {
    companion object {
        val TAG: String = BrowserTab::class.java.simpleName

        fun fromEntity(entity: TabEntity) = BrowserTab(entity.id, entity.url, entity.title, entity.selected,
            entity.thumbnailHash, entity.faviconHash, entity.incognito, entity.position, entity.wvStateFileName,
            entity.adblock, entity.scale)
    }

    fun toEntity() = TabEntity(id, url, title, selected, thumbnailHash, faviconHash, incognito, position,
        wvStateFileName, adblock, scale)

    /** The picture of the page; the file is written when the tab is saved */
    var thumbnail: Bitmap? = null
    /** [thumbnail] was updated and is not saved yet */
    private var thumbnailChanged = false
    var savedState: Any? = null
    private val webEngineHolder = lazy { WebEngineFactory.createWebEngine(this) }
    /** Created on the first access (usually when the tab is shown for the first time) */
    val webEngine: WebEngine get() = webEngineHolder.value
    val isWebEngineCreated: Boolean get() = webEngineHolder.isInitialized()
    var lastLoadingUrl: String? = null //this is last url appeared in WebViewClient.shouldOverrideUrlLoading callback
    var blockedAds = 0
    var blockedPopups = 0
    var cachedHostConfig: HostConfig? = null
    /** The page has a certificate error; cleared when the user navigates somewhere */
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
