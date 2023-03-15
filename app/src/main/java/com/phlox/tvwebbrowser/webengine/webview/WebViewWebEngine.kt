package com.phlox.tvwebbrowser.webengine.webview

import android.app.Activity
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.phlox.tvwebbrowser.activity.main.view.CursorLayout
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback

class WebViewWebEngine(val tab: WebTabState) : WebEngine {
    private var webView: WebViewEx? = null
    internal var callback: WebEngineWindowProviderCallback? = null
    private var fullscreenViewParent: ViewGroup? = null
    private var fullScreenView: View? = null
    private val permissionsRequests = HashMap<Int, Boolean>()//request code, isGeolocationPermissionRequest
    private val jsInterface = AndroidJSInterface(this)

    override val url: String?
        get() = webView?.url

    override var userAgentString: String
        get() = webView?.settings?.userAgentString ?: ""
        set(value) {
            webView?.settings?.userAgentString = value
        }

    override fun saveState(outState: Bundle) {
        webView?.saveState(outState)
    }

    override fun restoreState(savedInstanceState: Bundle) {
        webView?.restoreState(savedInstanceState)
    }

    override fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    override fun canGoForward(): Boolean {
        return webView?.canGoForward() ?: false
    }

    override fun goForward() {
        webView?.goForward()
    }

    override fun canZoomIn(): Boolean {
        return webView?.canZoomIn() ?: false
    }

    override fun zoomIn() {
        webView?.zoomIn()
    }

    override fun canZoomOut(): Boolean {
        return webView?.canZoomOut() ?: false
    }

    override fun zoomOut() {
        webView?.zoomOut()
    }

    override fun zoomBy(zoomBy: Float) {
        webView?.zoomBy(zoomBy)
    }

    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        webView?.evaluateJavascript(script, resultCallback)
    }

    override fun setNetworkAvailable(connected: Boolean) {
        webView?.setNetworkAvailable(connected)
    }

    override fun getView(): View? {
        return webView
    }

    @Throws(Exception::class)
    override fun getOrCreateView(activityContext: Context): View {
        if (webView == null) {
            webView = WebViewEx(activityContext, webViewCallback, jsInterface)
        }
        return webView!!
    }

    override fun canGoBack(): Boolean {
        return webView?.canGoBack() ?: false
    }

    override fun goBack() {
        webView?.goBack()
    }

    override fun reload() {
        webView?.reload()
    }

    override fun onFilePicked(data: Intent) {
        webView?.onFilePicked(data)
    }

    override fun onResume() {
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
    }

    override fun onUpdateAdblockSetting(newState: Boolean) {
        webView?.onUpdateAdblockSetting(newState)
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        webView?.clearCache(includeDiskFiles)
    }

    override fun hideCustomView() {
        webView?.hideCustomView()
    }

    override fun togglePlayback() {
        webView?.togglePlayback()
    }

    override fun renderThumbnail(thumbnail: Bitmap?): Bitmap? {
        return webView?.renderThumbnail(thumbnail)
    }

    override fun onAttachToWindow(callback: WebEngineWindowProviderCallback, parent: ViewGroup,
                                  fullscreenViewParent: ViewGroup) {
        this.callback = callback
        if (webView == null) {
            throw IllegalStateException("WebView is null")
        }
        this.fullscreenViewParent = fullscreenViewParent
        parent.removeAllViews()
        fullscreenViewParent.removeAllViews()
        parent.addView(webView)
        onResume()
    }

    override fun onDetachFromWindow(completely: Boolean, destroyTab: Boolean) {
        onPause()
        (webView?.parent as? ViewGroup)?.removeView(webView)
        callback = null
        if (completely) {
            webView = null
        }
    }

    override fun trimMemory() {
        val webView = webView
        if (webView != null && !webView.isAttachedToWindow) {
            this.webView = null
        }
    }

    override fun onPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
        val isGeolocationPermissionRequest = permissionsRequests[requestCode] ?: return false
        permissionsRequests.remove(requestCode)
        webView?.onPermissionsResult(permissions, grantResults, isGeolocationPermissionRequest)
        return true
    }

    private val webViewCallback = object : WebViewEx.Callback {
        override fun getActivity(): Activity? {
            return callback?.getActivity()
        }

        override fun onOpenInNewTabRequested(url: String) {
            callback?.onOpenInNewTabRequested(url)
        }

        override fun onDownloadRequested(url: String) {
            callback?.onDownloadRequested(url)
        }

        override fun onLongTap() {
            callback?.onLongTap()
        }

        override fun onThumbnailError() {
            //nop for now
        }

        override fun onShowCustomView(view: View) {
            webView?.visibility = View.GONE
            fullscreenViewParent?.apply {
                visibility = View.VISIBLE
                addView(view)
                val previousCursorPosition = (webView?.parent as? CursorLayout)?.cursorPosition
                if (previousCursorPosition != null) {
                    (this as? CursorLayout)?.cursorPosition?.set(previousCursorPosition)
                }
            }            
            fullScreenView = view
        }

        override fun onHideCustomView() {
            if (fullScreenView != null) {
                fullscreenViewParent?.removeView(fullScreenView)
                fullScreenView = null
            }
            val previousCursorPosition = (fullscreenViewParent as? CursorLayout)?.cursorPosition
            if (previousCursorPosition != null) {
                (webView?.parent as? CursorLayout)?.cursorPosition?.set(previousCursorPosition)
            }
            fullscreenViewParent?.visibility = View.INVISIBLE
            webView?.visibility = View.VISIBLE
        }

        override fun onProgressChanged(newProgress: Int) {
            callback?.onProgressChanged(newProgress)
        }

        override fun onReceivedTitle(title: String) {
            callback?.onReceivedTitle(title)
        }

        override fun requestPermissions(array: Array<String>, geo: Boolean) {
            val requestCode = callback?.requestPermissions(array) ?: return
            permissionsRequests[requestCode] = geo
        }

        override fun onShowFileChooser(intent: Intent): Boolean {
            return callback?.onShowFileChooser(intent) ?: false
        }

        override fun onReceivedIcon(icon: Bitmap) {
            callback?.onReceivedIcon(icon)
        }

        override fun shouldOverrideUrlLoading(url: String): Boolean {
            return callback?.shouldOverrideUrlLoading(url) ?: false
        }

        override fun onPageStarted(url: String?) {
            callback?.onPageStarted(url)
        }

        override fun onPageFinished(url: String?) {
            callback?.onPageFinished(url)
        }

        override fun onPageCertificateError(url: String?) {
            callback?.onPageCertificateError(url)
        }

        override fun isAd(request: WebResourceRequest, baseUri: Uri): Boolean {
            return callback?.isAd(request, baseUri) ?: false
        }

        override fun isAdBlockingEnabled(): Boolean {
            return callback?.isAdBlockingEnabled() ?: false
        }

        override fun isDialogsBlockingEnabled(): Boolean {
            return callback?.isDialogsBlockingEnabled() ?: false
        }

        override fun onBlockedAd(url: Uri) {
            callback?.onBlockedAd(url)
        }

        override fun onBlockedDialog(newTab: Boolean) {
            callback?.onBlockedDialog(newTab)
        }

        override fun onCreateWindow(dialog: Boolean, userGesture: Boolean): WebViewEx? {
            return callback?.onCreateWindow(dialog, userGesture) as? WebViewEx
        }

        override fun closeWindow(window: WebView) {
            callback?.closeWindow(window)
        }

        override fun onDownloadStart(url: String, userAgent: String, contentDisposition: String, mimetype: String?, contentLength: Long) {
            callback?.onDownloadStart(url, userAgent, contentDisposition, mimetype, contentLength)
        }

        override fun onScaleChanged(oldScale: Float, newScale: Float) {
            callback?.onScaleChanged(oldScale, newScale)
        }

        override fun onCopyTextToClipboardRequested(url: String) {
            callback?.onCopyTextToClipboardRequested(url)
        }

        override fun onShareUrlRequested(url: String) {
            callback?.onShareUrlRequested(url)
        }

        override fun onOpenInExternalAppRequested(url: String) {
            callback?.onOpenInExternalAppRequested(url)
        }
    }
}