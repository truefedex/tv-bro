package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.UiThread
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.view.CursorLayout
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import com.phlox.tvwebbrowser.webengine.gecko.delegates.*
import org.json.JSONObject
import org.mozilla.geckoview.*
import org.mozilla.geckoview.GeckoSession.SessionState
import org.mozilla.geckoview.WebExtension.MessageDelegate
import java.lang.ref.WeakReference

class GeckoWebEngine(val tab: WebTabState): WebEngine {
    companion object {
        lateinit var runtime: GeckoRuntime
        var appWebExtension = ObservableValue<WebExtension?>(null)

        @UiThread
        fun initialize(context: Context, webViewContainer: CursorLayout) {
            if (!this::runtime.isInitialized) {
                val builder = GeckoRuntimeSettings.Builder()
                if (BuildConfig.DEBUG) {
                    builder.remoteDebuggingEnabled(true)
                    builder.consoleOutput(true)
                }
                runtime = GeckoRuntime.create(context, builder.build())

                runtime.webExtensionController
                    //.installBuiltIn("resource://android/assets/extensions/generic/")
                    .ensureBuiltIn("resource://android/assets/extensions/generic/", "tvbro@mock.com")
                    .accept({ extension ->
                        Log.d(TAG, "extension accepted: ${extension?.metaData?.description}")
                        appWebExtension.value = extension
                    }
                    ) { e -> Log.e(TAG, "Error registering WebExtension", e) }
            }

            val webView = GeckoViewWithVirtualCursor(context)
            webViewContainer.addView(webView)
            weakRefToSingleGeckoView = WeakReference(webView)
            webViewContainer.setWillNotDraw(true)//use it only as a container, cursor will be drawn on WebView itself
        }

        val TAG: String = GeckoWebEngine::class.java.simpleName
        var weakRefToSingleGeckoView: WeakReference<GeckoViewWithVirtualCursor?> = WeakReference(null)
    }

    private var webView: GeckoViewWithVirtualCursor? = null
    val session = GeckoSession()
    var callback: WebEngineWindowProviderCallback? = null
    val navigationDelegate = MyNavigationDelegate(this)
    val progressDelegate = MyProgressDelegate(this)
    val promptDelegate = MyPromptDelegate(this)
    val contentDelegate = MyContentDelegate(this)
    val permissionDelegate = MyPermissionDelegate(this)
    val historyDelegate = MyHistoryDelegate(this)
    var appWebExtensionPortDelegate: AppWebExtensionPortDelegate? = null
    private lateinit var webExtObserver: (WebExtension?) -> Unit

    override val url: String?
        get() = navigationDelegate.locationURL
    override var userAgentString: String
        get() = session.settings.userAgentOverride ?: ""
        set(value) { session.settings.userAgentOverride = value }

    init {
        Log.d(TAG, "init")
        session.navigationDelegate = navigationDelegate
        session.progressDelegate = progressDelegate
        session.contentDelegate = contentDelegate
        session.promptDelegate = promptDelegate
        session.permissionDelegate = permissionDelegate
        session.historyDelegate = historyDelegate

        webExtObserver = { ext: WebExtension? ->
            if (ext != null) {
                connectToAppWebExtension(ext)
                appWebExtension.unsubscribe(webExtObserver)
            }
        }
        appWebExtension.subscribe(webExtObserver)
    }

    private fun connectToAppWebExtension(extension: WebExtension) {
        Log.d(TAG, "connectToAppWebExtension")
        session.webExtensionController.setMessageDelegate(extension,
            object : MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any,
                                       sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    Log.d(TAG, "onMessage: $nativeApp, $message, $sender")
                    return null
                }

                override fun onConnect(port: WebExtension.Port) {
                    Log.d(TAG, "onConnect: $port")
                    appWebExtensionPortDelegate = AppWebExtensionPortDelegate(port, this@GeckoWebEngine).also {
                        port.setDelegate(it)
                    }
                }
            }, "tvbro")
    }

    override fun saveState(): Any? {
        Log.d(TAG, "saveState")
        progressDelegate.sessionState?.let {
            return it
        }
        return null
    }

    override fun restoreState(savedInstanceState: Any) {
        Log.d(TAG, "restoreState")
        if (savedInstanceState is SessionState) {
            progressDelegate.sessionState = savedInstanceState
            if (!session.isOpen) {
                session.open(runtime)
            }
            session.restoreState(savedInstanceState)
        } else {
            throw IllegalArgumentException("savedInstanceState is not SessionState")
        }
    }

    override fun stateFromBytes(bytes: ByteArray): Any? {
        val jsString = String(bytes, Charsets.UTF_8)
        return SessionState.fromString(jsString)
    }

    override fun loadUrl(url: String) {
        Log.d(TAG, "loadUrl($url)")
        if (!session.isOpen) {
            session.open(runtime)
        }
        if (Config.HOME_URL_ALIAS == url) {
            when (TVBro.config.homePageMode) {
                Config.HomePageMode.BLANK -> {
                    //nothing to do
                }
                Config.HomePageMode.CUSTOM, Config.HomePageMode.SEARCH_ENGINE -> {
                    session.loadUri(TVBro.config.homePage)
                }
                Config.HomePageMode.HOME_PAGE -> {
                    //if (HomePageHelper.homePageFilesReady) {
                        session.loadUri(Config.HOME_PAGE_URL/*HomePageHelper.HOME_PAGE_URL*/)
                    //} else {
                    //    Toast.makeText(TVBro.instance, R.string.error, Toast.LENGTH_SHORT).show()
                    //}
                }
            }
        } else {
            session.loadUri(url)
        }
    }

    override fun canGoForward(): Boolean {
        return navigationDelegate.canGoForward
    }

    override fun goForward() {
        session.goForward()
    }

    override fun canZoomIn(): Boolean {
        return true
    }

    override fun zoomIn() {
        appWebExtensionPortDelegate?.port?.postMessage(JSONObject("{\"action\":\"zoomIn\"}"))
    }

    override fun canZoomOut(): Boolean {
        return true
    }

    override fun zoomOut() {
        appWebExtensionPortDelegate?.port?.postMessage(JSONObject("{\"action\":\"zoomOut\"}"))
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

    override fun onFilePicked(resultCode: Int, data: Intent?) {
        promptDelegate.onFileCallbackResult(resultCode, data)
    }

    override fun onResume() {
        session.setFocused(true)
    }

    override fun onPause() {
        session.setFocused(false)
    }

    override fun onUpdateAdblockSetting(newState: Boolean) {
        
    }

    override fun clearCache(includeDiskFiles: Boolean) {

    }

    override fun hideFullscreenView() {
        session.exitFullScreen()
    }

    override fun togglePlayback() {

    }

    override suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        return webView?.renderThumbnail(bitmap)
    }

    override fun onAttachToWindow(
        callback: WebEngineWindowProviderCallback,
        parent: ViewGroup, fullscreenViewParent: ViewGroup
    ) {
        Log.d(TAG, "onAttachToWindow()")
        this.callback = callback
        val webView = this.webView ?: throw IllegalStateException("WebView is null")
        val previousSession = webView.session
        if (previousSession != null && previousSession != session) {
            Log.d(TAG, "Closing previous session")
            previousSession.setActive(false)
            webView.releaseSession()
        }
        webView.coverUntilFirstPaint(Color.WHITE)
        webView.setSession(session)
        if (session.isOpen && previousSession != null && previousSession != session) {
            Log.d(TAG, "Activating session")
            session.setActive(true)
            session.reload()
        }
    }

    override fun onDetachFromWindow(completely: Boolean, destroyTab: Boolean) {
        Log.d(TAG, "onDetachFromWindow()")
        callback = null
        val webView = this.webView
        if (webView != null) {
            val session = webView.session
            if (session == this.session) {
                Log.d(TAG, "Closing session")
                session.setActive(false)
                webView.releaseSession()
            }
        }
        if (completely) {
            this.webView = null
        }
        if (destroyTab) {
            Log.d(TAG, "Closing session completely")
            session.close()
        }
    }

    override fun trimMemory() {
    }

    override fun onPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
        return permissionDelegate.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}