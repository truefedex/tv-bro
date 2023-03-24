package com.phlox.tvwebbrowser.webengine

import android.content.Context
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.view.CursorLayout
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import com.phlox.tvwebbrowser.webengine.webview.WebViewWebEngine

object WebEngineFactory {
    fun initialize(context: Context, webViewContainer: CursorLayout) {
        if (TVBro.config.isWebEngineGecko()) {
            GeckoWebEngine.initialize(context, webViewContainer)
        }
    }

    @Suppress("KotlinConstantConditions")
    fun createWebEngine(tab: WebTabState): WebEngine {
        return if (TVBro.config.isWebEngineGecko())
            GeckoWebEngine(tab)
        else
            WebViewWebEngine(tab)
    }
}