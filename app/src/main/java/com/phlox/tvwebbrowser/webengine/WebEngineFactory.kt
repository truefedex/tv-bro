package com.phlox.tvwebbrowser.webengine

import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.webengine.webview.WebViewWebEngine

object WebEngineFactory {
    fun createWebEngine(tab: WebTabState): WebEngine {
        return WebViewWebEngine(tab)
    }
}