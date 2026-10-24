package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import org.json.JSONObject

/**
 * Element hiding of the ad blocker in a WebView: `element_hiding.js` runs at the start of every document of
 * every frame (DOCUMENT_START_SCRIPT), or else in the main frame when the page starts and when it finishes.
 * It asks the `TVBroHiding` bridge synchronously, so the CSS of the page is there before its elements show.
 * WebView runs no such script in frames of about:blank and about:srcdoc: the script of their page hides
 * their elements (with the selectors of that page).
 * The bridge runs on a thread of WebView; [topUrl] gives the page of the tab for frames, whose site setting
 * applies (the top frame is the page itself: [topUrl] may still be the previous page there).
 */
internal class WebViewElementHiding(private val webView: WebView, private val topUrl: () -> Uri?) {
    private val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

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

    /** What the script asks; any page can call it, it only tells the CSS of a page */
    private inner class Bridge {
        @JavascriptInterface
        fun styles(url: String?, top: Boolean): String {
            val blocker = WebEngineFactory.adBlocker
            val styles = if (blocker == null || url == null) null else Uri.parse(url).let { blocker.hidingStyles(it, page(it, top)) }
            return JSONObject().put("css", styles?.css ?: "").put("generic", styles?.generic ?: false).toString()
        }

        @JavascriptInterface
        fun generic(url: String?, top: Boolean, classes: String?, ids: String?): String {
            val blocker = WebEngineFactory.adBlocker ?: return ""
            if (url == null) return ""
            val document = Uri.parse(url)
            return blocker.genericHidingStyles(document, page(document, top), names(classes), names(ids))
        }

        private fun page(document: Uri, top: Boolean): Uri? = if (top) document else topUrl()

        private fun names(text: String?): List<String> = text?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()
    }

    companion object {
        private const val BRIDGE = "TVBroHiding"
        private var script: String? = null

        /** The shared script in a function of its own, started with the bridge */
        private fun script(context: Context): String = script ?: ("(function () {\n" +
                context.assets.open("element_hiding.js").bufferedReader().use { it.readText() } + """
                if (!window.$BRIDGE) return;
                const bridge = window.$BRIDGE;
                const top = window === window.top;
                tvBroHideElements({
                    styles: callback => callback(JSON.parse(bridge.styles(location.href, top))),
                    generic: (classes, ids, callback) =>
                        callback(bridge.generic(location.href, top, classes.join(' '), ids.join(' '))),
                    //WebView runs no document start script in frames of about:blank and about:srcdoc
                    frames: true
                });
            })();""").also { script = it }
    }
}
