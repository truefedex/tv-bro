package com.phlox.tvwebbrowser.webengine.webview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.net.AppProxy
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import com.phlox.tvwebbrowser.webengine.EngineSpecificSetting
import com.phlox.tvwebbrowser.webengine.LoadErrorKind
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineProvider
import com.phlox.tvwebbrowser.webengine.WebEngineProviderCallback
import com.phlox.tvwebbrowser.webengine.WebEngineCallbacks
import com.phlox.tvwebbrowser.webengine.VirtualCursorSupport
import com.phlox.tvwebbrowser.webengine.SiteViewSupport
import com.phlox.tvwebbrowser.webengine.CursorModes
import com.phlox.tvwebbrowser.webengine.MediaPlaybackSupport
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.PageVideosSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import com.phlox.tvwebbrowser.widgets.cursor.CursorLayout
import com.phlox.tvwebbrowser.widgets.cursor.PointerMode


class WebViewWebEngine(val tab: BrowserTab) : WebEngine, VirtualCursorSupport, SiteViewSupport,
    MediaPlaybackSupport, PageVideosSupport, CursorDrawerDelegate.Callback {
    private var webView: WebViewEx? = null
    internal var callback: WebEngineCallbacks? = null
    /** The cursor surface around [webView] and the fullscreen view, created and dropped together with [webView] */
    private var cursorLayout: CursorLayout? = null
    private val _cursorModes = MutableStateFlow(CursorModes())
    override val cursorModes: StateFlow<CursorModes> = _cursorModes.asStateFlow()
    private var fullScreenView: View? = null
    private val _mediaState = MutableStateFlow<MediaState?>(null)
    override val mediaState: StateFlow<MediaState?> = _mediaState.asStateFlow()
    private val _pageVideos = MutableStateFlow<List<PageVideo>>(emptyList())
    override val pageVideos: StateFlow<List<PageVideo>> = _pageVideos.asStateFlow()
    private val _mediaVideo = MutableStateFlow<PageVideo?>(null)
    override val mediaVideo: StateFlow<PageVideo?> = _mediaVideo.asStateFlow()
    private val jsInterface = AndroidJSInterface(this)
    /** The page whose renderer died while the tab was shown: the next view shows the page of the crash for it */
    private var lostPage: LostPage? = null

    private class LostPage(val url: String, val crashed: Boolean)

    override fun getWebEngineName(): String = "WebView"

    override fun getCookies(url: String): String? = CookieManager.getInstance().getCookie(url)

    override fun isSameSession(internalRepresentation: Any): Boolean {
        return internalRepresentation == webView
    }

    override val url: String?
        get() = webView?.url

    override val userAgentString: String?
        get() = webView?.settings?.userAgentString

    override var desktopView = false
        set(value) {
            field = value
            webView?.setDesktopView(value)
            applyPointerMode()
        }

    /** The cursor is a mouse for pages in the desktop view, a finger for the others */
    private fun applyPointerMode() {
        cursorLayout?.cursorDrawerDelegate?.pointerMode = if (desktopView) PointerMode.MOUSE else PointerMode.TOUCH
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
        if (callback?.shouldOverrideUrlLoading(url) == true) {
            return
        }
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
            val webView = WebViewEx(activityContext, webViewCallback, jsInterface)
            if (desktopView) webView.setDesktopView(true)
            cursorLayout = CursorLayout(activityContext).apply {
                addView(webView)
                cursorDrawerDelegate.callback = this@WebViewWebEngine
            }
            applyPointerMode()
            //a new cursor surface starts in the cursor mode
            _cursorModes.value = CursorModes()
            this.webView = webView
            lostPage?.let {
                lostPage = null
                webView.showLoadErrorPage(LoadErrorKind.CRASHED, it.url,
                    if (it.crashed) "RENDER_PROCESS_CRASHED" else "RENDER_PROCESS_KILLED")
            }
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

    override fun stopLoading() {
        webView?.stopLoading()
    }

    override fun onResume() {
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
    }

    override fun hideFullscreenView() {
        webView?.hideCustomView()
    }

    override fun togglePlayback() {
        webView?.mediaCommand(MediaCommand.TOGGLE)
    }

    override fun stopPlayback() {
        webView?.mediaCommand(MediaCommand.STOP)
    }

    override fun rewind() {
        webView?.mediaCommand(MediaCommand.REWIND)
    }

    override fun fastForward() {
        webView?.mediaCommand(MediaCommand.FAST_FORWARD)
    }

    override fun seekTo(position: Double) {
        webView?.seekMediaTo(position)
    }

    override fun seekBy(delta: Double) {
        webView?.seekMediaBy(delta)
    }

    override fun pausePlayback() {
        webView?.mediaCommand(MediaCommand.PAUSE)
    }

    override suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        return webView?.renderThumbnail(bitmap)
    }

    override fun onAttachToWindow(callback: WebEngineCallbacks, parent: ViewGroup) {
        this.callback = callback
        val cursorLayout = cursorLayout ?: throw IllegalStateException("WebView is null")
        (cursorLayout.parent as? ViewGroup)?.removeView(cursorLayout)
        parent.removeAllViews()
        parent.addView(cursorLayout)
        onResume()
    }

    override fun onDetachFromWindow(completely: Boolean, destroyTab: Boolean) {
        onPause()
        cursorLayout?.let { (it.parent as? ViewGroup)?.removeView(it) }
        callback = null
        if (completely) {
            dropView()
        }
    }

    override fun trimMemory() {
        val webView = webView
        if (webView != null && !webView.isAttachedToWindow) {
            dropView()
        }
    }

    /** Forgets the view and its cursor surface (taking them out of the window); a new one is created when needed */
    private fun dropView() {
        cursorLayout?.let { (it.parent as? ViewGroup)?.removeView(it) }
        webView = null
        cursorLayout = null
        _mediaState.value = null
        _pageVideos.value = emptyList()
        _mediaVideo.value = null
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
        return cursorLayout?.cursorEnabled ?: true
    }

    override fun setVirtualCursorMode(enabled: Boolean) {
        val cursorLayout = cursorLayout ?: return
        cursorLayout.cursorEnabled = enabled
        if (enabled) {
            cursorLayout.cursorDrawerDelegate.animateAppearing()
        }
        webView?.setVirtualCursorMode(enabled)
        _cursorModes.update { it.copy(directNavigation = !enabled) }
    }

    override fun onGrabModeChanged(grabMode: Boolean) {
        _cursorModes.update { it.copy(grab = grabMode) }
    }

    override fun getCursorDrawerDelegate(): CursorDrawerDelegate? {
        return cursorLayout?.cursorDrawerDelegate
    }

    private val webViewCallback = object : WebViewEx.Callback {
        override fun getActivity(): Activity? {
            return callback?.getActivity()
        }

        override fun onOpenInNewTabRequested(url: String) {
            callback?.onOpenInNewTabRequested(url, true)
        }

        override fun onDownloadRequested(url: String) {
            callback?.onDownloadRequested(DownloadRequest.forLink(url, tab.url, userAgentString, getCookies(url)))
        }

        override fun onThumbnailError() {
            //nop for now
        }

        override fun onShowCustomView(view: View) {
            callback?.onPrepareForFullscreen()
            webView?.visibility = View.GONE
            cursorLayout?.apply {
                addView(view)
            }
            fullScreenView = view
        }

        override fun onHideCustomView() {
            if (fullScreenView != null) {
                cursorLayout?.removeView(fullScreenView)
                fullScreenView = null
            }
            webView?.visibility = View.VISIBLE
            callback?.onExitFullscreen()
        }

        override fun onRenderProcessGone(didCrash: Boolean) {
            val webView = webView ?: return
            val url = webView.url ?: tab.url
            if (fullScreenView != null) {
                fullScreenView = null
                callback?.onExitFullscreen()
            }
            dropView()
            webView.destroy()
            //a background tab gets a new view, restored from its saved state, when it is shown;
            //the current one shows the page of the crash in a new view now
            val callback = callback ?: return
            this@WebViewWebEngine.callback = null
            lostPage = LostPage(url, didCrash)
            callback.onEngineViewLost()
        }

        override fun onProgressChanged(newProgress: Int) {
            callback?.onProgressChanged(newProgress)
        }

        override fun onReceivedTitle(title: String) {
            callback?.onReceivedTitle(title)
        }

        override fun requestPermissions(array: Array<String>, geo: Boolean) {
            callback?.requestPermissions(array) { results ->
                // no results (the request was cancelled) deny: the page waits for an answer
                val grantResults = IntArray(array.size) {
                    if (results[array[it]] == true) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                }
                webView?.onPermissionsResult(array, grantResults, geo)
            }
        }

        override fun onShowFileChooser(intent: Intent): Boolean {
            return callback?.pickFile(intent) { resultCode, data ->
                webView?.onFilePicked(resultCode, data)
            } ?: false
        }

        override fun onReceivedIcon(icon: Bitmap) {
            callback?.onReceivedIcon(icon)
        }

        override fun shouldOverrideUrlLoading(url: String): Boolean {
            return callback?.shouldOverrideUrlLoading(url) ?: false
        }

        override fun switchSiteView(url: String): Boolean {
            val desktop = callback?.isDesktopView(url) ?: return false
            if (desktop == desktopView) return false
            desktopView = desktop
            return true
        }

        override fun onPageStarted(url: String?) {
            callback?.onPageStarted(url)
        }

        override fun onScrolled(scrollY: Int, dy: Int) {
            callback?.onPageScrolled(scrollY, dy)
        }

        override fun onPageFinished(url: String?) {
            callback?.onPageFinished(url)
        }

        override fun onPageCertificateError(url: String?) {
            callback?.onPageCertificateError(url)
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
            callback?.onDownloadRequested(DownloadRequest(url = url, referer = tab.url, userAgent = userAgent, cookies = getCookies(url),
                mimeType = mimetype, contentDisposition = contentDisposition, size = contentLength))
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

        override fun onMediaStateChanged(state: MediaState?) {
            _mediaState.value = state
        }

        override fun onPageVideosChanged(videos: List<PageVideo>) {
            _pageVideos.value = videos
        }

        override fun onMediaVideoChanged(video: PageVideo?) {
            _mediaVideo.value = video
        }

        override fun onContextMenu(baseUrl:String?, href: String?, x: Int, y: Int) {
            callback?.onContextMenu(
                cursorLayout!!.cursorDrawerDelegate,
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
        init {
            WebEngineFactory.registerProvider(WebEngineProvider("WebView", object : WebEngineProviderCallback {
                override suspend fun initialize(context: Context, webViewContainer: ViewGroup) {
                    //before any WebView of the tabs loads a page
                    AppProxy.settings?.let { WebViewProxy.apply(it) }
                }

                override suspend fun applyProxy(proxy: ProxySettings?) {
                    WebViewProxy.apply(proxy)
                }

                override fun createWebEngine(tab: BrowserTab): WebEngine {
                    return WebViewWebEngine(tab)
                }

                override suspend fun clearCache(ctx: Context) {
                    WebView(ctx).clearCache(true)
                }

                override fun onThemeSettingUpdated(value: Config.Theme) {
                    //nop
                }

                override fun getWebEngineVersionString(): String {
                    val webViewPackage = WebViewCompat.getCurrentWebViewPackage(AppContext.get())
                    return (webViewPackage?.packageName ?: "unknown") + ":" + (webViewPackage?.versionName ?: "unknown")
                }

                override fun prepareIncognitoProcess() {
                    WebViewIncognitoData.prepareIncognitoProcess()
                }

                override suspend fun clearIncognitoBrowsingData(context: Context) {
                    WebViewIncognitoData.clearBrowsingData(context)
                }

                override suspend fun deleteIncognitoData() {
                    WebViewIncognitoData.deleteData()
                }

                override fun supportsSiteDataClearing(): Boolean = true

                override suspend fun clearSiteData(context: Context, host: String) {
                    WebViewSiteData.clear(context, host)
                }

                override fun onViewCreationFailed(activity: Activity, error: Throwable) {
                    showWebViewNotAvailableDialog(activity)
                }

                override fun supportsSetting(setting: EngineSpecificSetting): Boolean = when (setting) {
                    //the same features WebViewEx uses for it
                    EngineSpecificSetting.ALGORITHMIC_DARKENING -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
                    } else {
                        WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)
                    }
                    //WebView has no blocking of trackers of its own
                    EngineSpecificSetting.TRACKING_PROTECTION -> false
                    EngineSpecificSetting.PROXY -> WebViewProxy.isSupported()
                }
            }))
        }

        private fun showWebViewNotAvailableDialog(activity: Activity) {
            val dialogBuilder = AlertDialog.Builder(activity)
                .setTitle(R.string.error)
                .setCancelable(false)
                .setMessage(R.string.err_webview_can_not_link)
                .setNegativeButton(R.string.exit) { _, _ -> activity.finish() }

            val appPackageName = "com.google.android.webview"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName"))
            val activities = activity.packageManager.queryIntentActivities(intent, 0)
            if (activities.size > 0) {
                dialogBuilder.setPositiveButton(R.string.find_in_apps_store) { _, _ ->
                    try {
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    activity.finish()
                }
            }
            dialogBuilder.show()
        }
    }
}