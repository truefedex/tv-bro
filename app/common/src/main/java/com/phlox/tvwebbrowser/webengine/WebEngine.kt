package com.phlox.tvwebbrowser.webengine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate

interface WebEngine: CursorDrawerDelegate.TextSelectionCallback {
    val url: String?
    var userAgentString: String?

    fun getWebEngineName(): String
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
    fun onFilePicked(resultCode: Int, data: Intent?)
    fun onResume()
    fun onPause()
    fun onUpdateAdblockSetting(newState: Boolean)
    fun hideFullscreenView()
    fun togglePlayback()
    suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap?
    /**
     * At this point of time web view should be already created but not attached to window
     */
    fun onAttachToWindow(callback: WebEngineWindowProviderCallback, parent: ViewGroup)
    fun onDetachFromWindow(completely: Boolean, destroyTab: Boolean)
    fun trimMemory()
    fun onPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean
    fun isSameSession(internalRepresentation: Any): Boolean
    fun replaceSelection(newText: String)
    fun stopPlayback()
    fun rewind()
    fun fastForward()
    fun setVirtualCursorMode(enabled: Boolean)
    fun isVirtualCursorMode(): Boolean
    fun getCursorDrawerDelegate(): CursorDrawerDelegate?
}