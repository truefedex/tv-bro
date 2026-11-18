package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.webengine.PageZoom
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import org.json.JSONObject

/**
 * The viewport of the page in a WebView (`page_zoom.js`): the zoom of its host ([PageZoom]) and the width of the
 * desktop view: pages that set their viewport to the width of the device get at least the width of a desktop
 * page, as in the desktop view of Chrome (TVs are about as wide already). The script runs at the start of every
 * document (DOCUMENT_START_SCRIPT), or else in the main frame when the page starts and when it finishes, and asks
 * the `TVBroZoom` bridge synchronously, so the page lays out once with its viewport.
 */
internal class WebViewPageZoom(private val webView: WebView) {
    private val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** Pages load in a wide view (desktop, tablet); read by the bridge on a thread of WebView */
    @Volatile
    var wideView = false

    init {
        webView.addJavascriptInterface(Bridge(), BRIDGE)
        if (documentStart) WebViewCompat.addDocumentStartJavaScript(webView, script(webView.context), setOf("*"))
    }

    /** Without document start scripts the script runs at both (it starts once per document) */
    fun onPageStarted() {
        if (!documentStart) webView.evaluateJavascript(script(webView.context), null)
    }

    fun onPageFinished() {
        if (!documentStart) webView.evaluateJavascript(script(webView.context), null)
    }

    /**
     * Gives the page shown the [zoom] at once. WebView fits the new width into the screen only while the user did
     * not zoom (a pinch), so the page is zoomed out as far as it goes once it has the new width.
     */
    fun apply(zoom: Int) {
        val settings = settings(zoom)
        webView.evaluateJavascript("window.tvBroPageZoomApi && window.tvBroPageZoomApi.set($settings);") {
            webView.postDelayed({ webView.zoomBy(MIN_ZOOM_BY) }, FIT_DELAY_MS)
        }
    }

    /** Tells the zoom of the page relative to its width (page_zoom.js) once a zoom just started has settled */
    fun visualZoom(callback: (Double?) -> Unit) {
        webView.postDelayed({
            webView.evaluateJavascript("window.tvBroPageZoomApi ? window.tvBroPageZoomApi.visualZoom() : null") {
                callback(it?.toDoubleOrNull())
            }
        }, ZOOM_SETTLE_MS)
    }

    private fun settings(zoom: Int): String =
        JSONObject().put("zoom", zoom).put("desktopWidth", if (wideView) DESKTOP_WIDTH else 0).toString()

    /** What the script asks; any page can call it, it only tells the zoom of a page */
    private inner class Bridge {
        @JavascriptInterface
        fun settings(url: String?): String {
            val zoom = url?.let { WebEngineFactory.pageZooms?.zoomOf(it) } ?: PageZoom.DEFAULT
            return settings(zoom)
        }
    }

    companion object {
        private const val BRIDGE = "TVBroZoom"
        /** The width of the layout of a desktop page in CSS pixels, as in the desktop view of Chrome */
        private const val DESKTOP_WIDTH = 980
        /** A factor of [WebView.zoomBy] near its least (it must be above 0.01): the scale stops where the page fits */
        private const val MIN_ZOOM_BY = 0.02f
        /** The time the page takes to lay out with its new viewport */
        private const val FIT_DELAY_MS = 150L
        /** The time a zoom of WebView takes */
        private const val ZOOM_SETTLE_MS = 300L
        private var script: String? = null

        /** The shared script in a function of its own, started with the bridge */
        private fun script(context: Context): String = script ?: ("(function () {\n" +
                context.assets.open("page_zoom.js").bufferedReader().use { it.readText() } + """
                if (!window.$BRIDGE || window !== window.top) return;
                tvBroPageZoom(JSON.parse(window.$BRIDGE.settings(location.href)));
            })();""").also { script = it }
    }
}
