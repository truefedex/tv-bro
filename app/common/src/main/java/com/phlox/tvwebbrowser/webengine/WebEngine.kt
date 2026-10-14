package com.phlox.tvwebbrowser.webengine

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup

interface WebEngine {
    val url: String?
    var userAgentString: String?

    fun getWebEngineName(): String

    /**
     * Prefix of the names of tab state files saved by this engine ("" or "name:"),
     * so that the state saved by another engine is not restored.
     */
    val stateFilePrefix: String get() = ""

    /**
     * Cookies of this engine for [url] ("Cookie" header value), to download the url outside of the engine.
     * Null if there are none or the engine does not share them (then its downloads should come with a stream).
     */
    fun getCookies(url: String): String? = null
    fun saveState(): Any?//Bundle or any Object convertible to string
    fun restoreState(savedInstanceState: Any)
    fun stateFromBytes(bytes: ByteArray): Any?
    fun loadUrl(url: String)
    fun canGoForward(): Boolean
    fun goForward()
    fun canZoomIn(): Boolean
    fun zoomIn()
    fun canZoomOut(): Boolean
    fun zoomOut()
    fun zoomBy(zoomBy: Float)
    fun evaluateJavascript(script: String)
    fun setNetworkAvailable(connected: Boolean)
    fun getView(): View?
    @Throws(Exception::class)
    fun getOrCreateView(activityContext: Context): View
    fun canGoBack(): Boolean
    fun goBack()
    fun reload()
    fun onResume()
    fun onPause()
    fun onUpdateAdblockSetting(newState: Boolean)
    fun hideFullscreenView()
    fun togglePlayback()
    suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap?
    /**
     * At this point of time web view should be already created but not attached to window
     */
    fun onAttachToWindow(callback: WebEngineCallbacks, parent: ViewGroup)
    fun onDetachFromWindow(completely: Boolean, destroyTab: Boolean)
    fun trimMemory()
    fun isSameSession(internalRepresentation: Any): Boolean
    fun stopPlayback()
    fun rewind()
    fun fastForward()
}