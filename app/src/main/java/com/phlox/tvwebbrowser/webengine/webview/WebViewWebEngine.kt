package com.phlox.tvwebbrowser.webengine.webview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineProvider
import com.phlox.tvwebbrowser.webengine.WebEngineProviderCallback
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import com.phlox.tvwebbrowser.widgets.cursor.CursorLayout


class WebViewWebEngine(val tab: WebTabState) : WebEngine, CursorDrawerDelegate.Callback {
    private var webView: WebViewEx? = null
    internal var callback: WebEngineWindowProviderCallback? = null
    private var viewParent: CursorLayout? = null
    private var fullScreenView: View? = null
    private val permissionsRequests = HashMap<Int, Boolean>()//request code, isGeolocationPermissionRequest
    private val jsInterface = AndroidJSInterface(this)

    override fun getWebEngineName(): String = "WebView"

    override fun isSameSession(internalRepresentation: Any): Boolean {
        return internalRepresentation == webView
    }

    override val url: String?
        get() = webView?.url

    override var userAgentString: String? = null
        set(value) {
            field = value
            if (value != null) {
                webView?.settings?.userAgentString = value
            }
        }

    override fun saveState(): Any {
        val bundle = Bundle()
        webView?.saveState(bundle)
        return bundle
    }

    override fun restoreState(savedInstanceState: Any) {
        if (savedInstanceState is Bundle) {
            webView?.restoreState(savedInstanceState)
        } else {
            throw IllegalArgumentException("savedInstanceState must be Bundle")
        }
    }

    override fun stateFromBytes(bytes: ByteArray): Any? =
        Utils.bytesToBundle(bytes)

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

    override fun evaluateJavascript(script: String) {
        webView?.evaluateJavascript(script, null)
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

    override fun onFilePicked(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return
        }
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

    override fun hideFullscreenView() {
        webView?.hideCustomView()
    }

    override fun togglePlayback() {
        webView?.evaluateJavascript("tvBroTogglePlayback()", null)
    }

    override fun stopPlayback() {
        webView?.evaluateJavascript("tvBroStopPlayback()", null)
    }

    override fun rewind() {
        webView?.evaluateJavascript("tvBroRewind()", null)
    }

    override fun fastForward() {
        webView?.evaluateJavascript("tvBroFastForward()", null)
    }

    override suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        return webView?.renderThumbnail(bitmap)
    }

    override fun onAttachToWindow(callback: WebEngineWindowProviderCallback, parent: ViewGroup) {
        this.callback = callback
        if (webView == null) {
            throw IllegalStateException("WebView is null")
        }
        this.viewParent = parent as CursorLayout
        parent.removeAllViews()
        parent.addView(webView)
        viewParent?.cursorDrawerDelegate?.callback = this
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
        if (grantResults.isEmpty()) return true
        webView?.onPermissionsResult(permissions, grantResults, isGeolocationPermissionRequest)
        return true
    }

    override fun onTextSelectionStart(x: Int, y: Int) {
        webView?. let {
            it.evaluateJavascript("TVBRO_clearSelection();TVBRO_updateSelection($x, $y, ${it.width}, ${it.height});") {
                //nop
            }
        }
    }

    override fun onTextSelectionMove(x: Int, y: Int) {
        webView?. let {
            it.evaluateJavascript("TVBRO_updateSelection($x, $y, ${it.width}, ${it.height});") {
                //nop
            }
        }
    }

    override fun onTextSelectionEnd(x: Int, y: Int) {
        //detect is selection in editable field (with js standard way)
        webView?. let {
            it.evaluateJavascript("TVBRO_processSelection()") { resultStr ->
                val unescaped: String = resultStr.trim('"')
                    .replace("\\\\", "\\") // unescape \\ -> \
                    .replace("\\\"", "\"")
                val result = Utils.jsonToMap(unescaped)
                val selectedText = result["selectedText"] as String
                val editable = result["editable"] as Boolean
                callback?.onSelectedTextActionRequested(selectedText, editable)
            }
        }
    }

    override fun onTextSelectionCancel() {
        webView?.let {
            it.evaluateJavascript("TVBRO_clearSelection();") {
                //nop
            }
        }
    }

    override fun replaceSelection(newText: String) {
        val escapedText = newText.replace("'", "\\'")
        webView?.let {
            it.evaluateJavascript("""
                let selection = window.getSelection();
                let range = selection.getRangeAt(0);
                range.deleteContents();
                range.insertNode(document.createTextNode('$escapedText'));
            """.trimIndent()) {
                //nop
            }
        }
    }

    override fun onLongPress(x: Int, y: Int) {
        webView?.let {
            it.evaluateJavascript(Scripts.LONG_PRESS_SCRIPT) { href ->
                val linkUrl = if (href == "null") null else href
                webViewCallback.onContextMenu(
                    it.currentOriginalUrl.toString(),
                    linkUrl, x, y
                )
            }
        }
    }

    override fun isVirtualCursorMode(): Boolean {
        return viewParent?.cursorEnabled ?: true
    }

    override fun setVirtualCursorMode(enabled: Boolean) {
        viewParent?.cursorEnabled = enabled
        if (enabled) {
            viewParent?.cursorDrawerDelegate?.animateAppearing()
        }
        webView?.setVirtualCursorMode(enabled)
    }

    override fun getCursorDrawerDelegate(): CursorDrawerDelegate? {
        return viewParent?.cursorDrawerDelegate
    }

    private val webViewCallback = object : WebViewEx.Callback {
        override fun getActivity(): Activity? {
            return callback?.getActivity()
        }

        override fun onOpenInNewTabRequested(url: String) {
            callback?.onOpenInNewTabRequested(url, true)
        }

        override fun onDownloadRequested(url: String) {
            callback?.onDownloadRequested(url)
        }

        override fun onThumbnailError() {
            //nop for now
        }

        override fun onShowCustomView(view: View) {
            callback?.onPrepareForFullscreen()
            webView?.visibility = View.GONE
            viewParent?.apply {
                addView(view)
            }
            fullScreenView = view
        }

        override fun onHideCustomView() {
            if (fullScreenView != null) {
                viewParent?.removeView(fullScreenView)
                fullScreenView = null
            }
            webView?.visibility = View.VISIBLE
            callback?.onExitFullscreen()
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
            return callback?.isAd(request.url, request.requestHeaders["Accept"], baseUri) ?: false
        }

        override fun isAdBlockingEnabled(): Boolean {
            return callback?.isAdBlockingEnabled() ?: false
        }

        override fun isDialogsBlockingEnabled(): Boolean {
            return callback?.isDialogsBlockingEnabled() ?: false
        }

        override fun onBlockedAd(url: Uri) {
            callback?.onBlockedAd(url.toString())
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
            callback?.onDownloadRequested(url, userAgent, contentDisposition, mimetype, contentLength)
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

        override fun onVisited(url: String) {
            callback?.onVisited(url)
        }

        override fun onContextMenu(baseUrl:String?, href: String?, x: Int, y: Int) {
            callback?.onContextMenu(
                viewParent!!.cursorDrawerDelegate,
                baseUri = baseUrl,
                linkUri = href,
                srcUri = null,
                title = null,
                altText = null,
                textContent = null,
                x = x,
                y = y
            )
        }
    }

    companion object {
        fun clearCache(ctx: Context) {
            WebView(ctx).clearCache(true)
        }

        init {
            WebEngineFactory.registerProvider(WebEngineProvider("WebView", object : WebEngineProviderCallback {
                override suspend fun initialize(context: Context, webViewContainer: CursorLayout) {

                }

                override fun createWebEngine(tab: WebTabState): WebEngine {
                    return WebViewWebEngine(tab)
                }

                override suspend fun clearCache(ctx: Context) {
                    clearCache(ctx)
                }

                override fun onThemeSettingUpdated(value: Config.Theme) {
                    //nop
                }

                override fun getWebEngineVersionString(): String {
                    val webViewPackage = WebViewCompat.getCurrentWebViewPackage(AppContext.get())
                    return (webViewPackage?.packageName ?: "unknown") + ":" + (webViewPackage?.versionName ?: "unknown")
                }
            }))
        }
    }
}