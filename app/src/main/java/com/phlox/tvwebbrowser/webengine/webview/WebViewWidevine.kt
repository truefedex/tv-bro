package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Widevine in a WebView: `widevine_robustness.js` at the start of every document of every frame
 * (DOCUMENT_START_SCRIPT, before the scripts of the page ask for DRM; WebViews without it go without). WebView gives
 * Widevine only at SW_SECURE_CRYPTO (L3), and a request for SW_SECURE_DECODE, which sites make where desktop Chrome
 * gives it (the desktop view), is asked again at SW_SECURE_CRYPTO.
 */
internal object WebViewWidevine {
    private var script: String? = null

    fun install(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addDocumentStartJavaScript(webView, script(webView.context), setOf("*"))
    }

    private fun script(context: Context): String = script
        ?: context.assets.open("widevine_robustness.js").bufferedReader().use { it.readText() }.also { script = it }
}
