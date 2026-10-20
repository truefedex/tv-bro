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
                 var incognito: Boolean = false,
                 var position: Int = 0,
                 var wvStateFileName: String? = null) {
    companion object {
        val TAG: String = BrowserTab::class.java.simpleName

        fun fromEntity(entity: TabEntity) = BrowserTab(entity.id, entity.url, entity.title, entity.selected,
            entity.thumbnailHash, entity.incognito, entity.position, entity.wvStateFileName)
    }

    fun toEntity() = TabEntity(id, url, title, selected, thumbnailHash, incognito, position, wvStateFileName)

    /** The picture of the page; the file is written when the tab is saved */
    var thumbnail: Bitmap? = null
    /** [thumbnail] was updated and is not saved yet */
    private var thumbnailChanged = false
    var savedState: Any? = null
    private val webEngineHolder = lazy { WebEngineFactory.createWebEngine(this) }
    /** Created on the first access (usually when the tab is shown for the first time) */
    val webEngine: WebEngine get() = webEngineHolder.value
    val isWebEngineCreated: Boolean get() = webEngineHolder.isInitialized()
    var blockedAds = 0
    var blockedPopups = 0
    /**
     * The host whose certificate was refused on this tab: its pages stay insecure (also after the user
     * went on despite the error) until the user navigates somewhere or the tab shows another host
     */
    var certificateErrorHost: String? = null
    /** A page is loading; known only while the tab is the current one (other tabs' engines report nothing) */
    var loading = false

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
