package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import com.phlox.tvwebbrowser.activity.main.view.CursorLayout
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyContentDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyNavigationDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyProgressDelegate
import org.mozilla.geckoview.*
import java.lang.ref.WeakReference

class GeckoWebEngine(val tab: WebTabState): WebEngine {
    companion object {
        lateinit var runtime: GeckoRuntime

        fun initialize(context: Context, webViewContainer: CursorLayout) {
            if (this::runtime.isInitialized) return
            val builder = GeckoRuntimeSettings.Builder()
            if (BuildConfig.DEBUG) {
                builder.remoteDebuggingEnabled(true)
            }
            builder.consoleOutput(true)
            runtime = GeckoRuntime.create(context, builder.build())

            val webView = GeckoViewWithVirtualCursor(context)
            webViewContainer.addView(webView)
            weakRefToSingleGeckoView = WeakReference(webView)
            webViewContainer.setWillNotDraw(true)//use it only as a container, cursor will be drawn on WebView itself
        }

        val TAG: String = GeckoWebEngine::class.java.simpleName
        var weakRefToSingleGeckoView: WeakReference<GeckoViewWithVirtualCursor?> = WeakReference(null)
    }

    private var webView: GeckoViewWithVirtualCursor? = null
    private val session = GeckoSession()
    var callback: WebEngineWindowProviderCallback? = null
    private val navigationDelegate = MyNavigationDelegate()
    private val progressDelegate = MyProgressDelegate(this)

    override val url: String?
        get() = navigationDelegate.locationURL
    override var userAgentString: String
        get() = session.settings.userAgentOverride ?: ""
        set(value) { session.settings.userAgentOverride = value }

    init {
        Log.d(TAG, "init")
        session.open(runtime)
        session.navigationDelegate = navigationDelegate
        session.progressDelegate = progressDelegate
        session.contentDelegate = MyContentDelegate(this)
    }

    override fun saveState(outState: Bundle) {
        progressDelegate.sessionState?.let {
            outState.putParcelable("geckoSessionState", it)
        }
    }

    override fun restoreState(savedInstanceState: Bundle) {
        savedInstanceState.classLoader = GeckoSession.SessionState::class.java.classLoader
        val sessionState = savedInstanceState.getParcelable<GeckoSession.SessionState>("geckoSessionState")
        if (sessionState != null) {
            progressDelegate.sessionState = sessionState
            session.restoreState(sessionState)
        }
    }

    override fun loadUrl(url: String) {
        Log.d(TAG, "loadUrl($url)")
        session.loadUri(url)
    }

    override fun canGoForward(): Boolean {
        return navigationDelegate.canGoForward
    }

    override fun goForward() {
        session.goForward()
    }

    override fun canZoomIn(): Boolean {
        return false
    }

    override fun zoomIn() {

    }

    override fun canZoomOut(): Boolean {
        return false
    }

    override fun zoomOut() {
        
    }

    override fun zoomBy(zoomBy: Float) {
        
    }

    override fun evaluateJavascript(script: String) {
        session.loadUri("javascript:$script")
    }

    override fun setNetworkAvailable(connected: Boolean) {

    }

    override fun getView(): View? {
        return webView
    }

    override fun getOrCreateView(activityContext: Context): View {
        Log.d(TAG, "getOrCreateView()")
        if (webView == null) {
            val geckoView = weakRefToSingleGeckoView.get()
            if (geckoView == null) {
                Log.i(TAG, "Creating new GeckoView")
                webView = GeckoViewWithVirtualCursor(activityContext)
                weakRefToSingleGeckoView = WeakReference(webView)
            } else {
                webView = geckoView
            }
        }
        return webView!!
    }

    override fun canGoBack(): Boolean {
        return navigationDelegate.canGoBack
    }

    override fun goBack() {
        session.goBack()
    }

    override fun reload() {
        session.reload()
    }

    override fun onFilePicked(data: Intent) {
        
    }

    override fun onResume() {
        
    }

    override fun onPause() {
        
    }

    override fun onUpdateAdblockSetting(newState: Boolean) {
        
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        
    }

    override fun hideCustomView() {
        
    }

    override fun togglePlayback() {
        
    }

    override fun renderThumbnail(thumbnail: Bitmap?): Bitmap? {
        return null
    }

    override fun onAttachToWindow(
        callback: WebEngineWindowProviderCallback,
        parent: ViewGroup,
        fullscreenViewParent: ViewGroup
    ) {
        Log.d(TAG, "onAttachToWindow()")
        this.callback = callback
        if (webView == null) {
            throw IllegalStateException("WebView is null")
        }
        webView!!.setSession(session)
    }

    override fun onDetachFromWindow(completely: Boolean, destroyTab: Boolean) {
        Log.d(TAG, "onDetachFromWindow()")
        callback = null
        if (webView != null) {
            webView!!.releaseSession()
        }
        if (completely) {
            webView = null
        }
        if (destroyTab) {
            session.close()
        }
    }

    override fun trimMemory() {
    }

    override fun onPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        return false
    }
}