package com.phlox.tvwebbrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineProvider
import com.phlox.tvwebbrowser.webengine.WebEngineProviderCallback
import com.phlox.tvwebbrowser.webengine.WebEngineCallbacks
import com.phlox.tvwebbrowser.webengine.SiteViewSupport

/**
 * Web engine that only records what is done with it.
 */
class FakeWebEngine(val tab: BrowserTab) : WebEngine, SiteViewSupport {
    companion object {
        val provider = WebEngineProvider("Fake", object : WebEngineProviderCallback {
            override suspend fun initialize(context: Context, webViewContainer: ViewGroup) {}
            override fun createWebEngine(tab: BrowserTab): WebEngine = FakeWebEngine(tab)
            override suspend fun clearCache(ctx: Context) {}
            override fun onThemeSettingUpdated(value: Config.Theme) {}
            override fun getWebEngineVersionString(): String = "fake"
            override fun supportsSiteDataClearing(): Boolean = true
            override suspend fun clearSiteData(context: Context, host: String) { clearedSites += host }
        })
        /** The hosts whose data was cleared */
        val clearedSites = ArrayList<String>()
    }

    val log = ArrayList<String>()
    val loadedUrls = ArrayList<String>()
    var callback: WebEngineCallbacks? = null
    var parent: ViewGroup? = null
    var engineView: View? = null
    var canGoBackValue = false
    override var desktopView = false

    override val url: String? get() = loadedUrls.lastOrNull()
    override val userAgentString: String? = null

    override fun getWebEngineName(): String = "Fake"
    override var stateFilePrefix: String = ""
    override fun saveState(): Any = "state of ${tab.url}"
    override fun restoreState(savedInstanceState: Any) { log += "restoreState" }
    override fun stateFromBytes(bytes: ByteArray): Any = String(bytes)
    override fun loadUrl(url: String) { loadedUrls += url }
    override fun canGoForward(): Boolean = false
    override fun goForward() { log += "goForward" }
    override fun canZoomIn(): Boolean = false
    override fun zoomIn() {}
    override fun canZoomOut(): Boolean = false
    override fun zoomOut() {}
    override fun zoomBy(zoomBy: Float) {}
    override fun evaluateJavascript(script: String) {}
    override fun setNetworkAvailable(connected: Boolean) {}
    override fun getView(): View? = engineView
    override fun getOrCreateView(activityContext: Context): View = engineView ?: View(activityContext).also { engineView = it }
    override fun canGoBack(): Boolean = canGoBackValue
    override fun goBack() { log += "goBack" }
    override fun reload() { log += "reload" }
    override fun stopLoading() { log += "stopLoading" }
    override fun onResume() { log += "resume" }
    override fun onPause() { log += "pause" }
    override fun hideFullscreenView() {}
    override fun togglePlayback() {}
    override suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap? = null

    override fun onAttachToWindow(callback: WebEngineCallbacks, parent: ViewGroup) {
        log += "attach"
        this.callback = callback
        this.parent = parent
    }

    override fun onDetachFromWindow(completely: Boolean, destroyTab: Boolean) {
        log += "detach(completely=$completely, destroyTab=$destroyTab)"
        callback = null
        parent = null
        if (completely) engineView = null
    }

    override fun trimMemory() { log += "trimMemory" }
    override fun isSameSession(internalRepresentation: Any): Boolean = internalRepresentation === this
    override fun stopPlayback() {}
    override fun rewind() {}
    override fun fastForward() {}
}

val BrowserTab.fakeEngine: FakeWebEngine get() = webEngine as FakeWebEngine
