package com.phlox.tvwebbrowser.webengine

import android.content.Context
import android.util.Log
import androidx.annotation.UiThread
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.widgets.cursor.CursorLayout

interface WebEngineProviderCallback {
    suspend fun initialize(context: Context, webViewContainer: CursorLayout)
    fun createWebEngine(tab: WebTabState): WebEngine
    suspend fun clearCache(ctx: Context)
    fun onThemeSettingUpdated(value: Config.Theme)
    fun getWebEngineVersionString(): String
}

data class WebEngineProvider(
    val name: String,
    val callback: WebEngineProviderCallback
)



object WebEngineFactory {
    const val TAG = "WebEngineFactory"
    private val engineProviders = mutableListOf<WebEngineProvider>()
    private lateinit var initializedProvider: WebEngineProvider

    fun registerProvider(provider: WebEngineProvider) {
        engineProviders.add(provider)
    }

    fun getProviders(): List<WebEngineProvider> {
        return engineProviders
    }

    @UiThread
    suspend fun initialize(context: Context, webViewContainer: CursorLayout) {
        val config = AppContext.provideConfig()
        var webEngineProvider = engineProviders.find { it.name == config.webEngine }
        if (webEngineProvider == null && engineProviders.isNotEmpty()) {
            webEngineProvider = engineProviders[0]
            Log.w(TAG, "WebEngineProvider with name ${config.webEngine} not found, using ${webEngineProvider.name}")
            config.webEngine = webEngineProvider.name
        }
        if (webEngineProvider != null) {
            webEngineProvider.callback.initialize(context, webViewContainer)
            initializedProvider = webEngineProvider
        } else {
            throw IllegalArgumentException("WebEngineProvider with name ${config.webEngine} not found")
        }
    }

    fun createWebEngine(tab: WebTabState): WebEngine {
        return initializedProvider.callback.createWebEngine(tab)
    }

    suspend fun clearCache(ctx: Context) {
        initializedProvider.callback.clearCache(ctx)
    }

    fun onThemeSettingUpdated(value: Config.Theme) {
        initializedProvider.callback.onThemeSettingUpdated(value)
    }

    fun getWebEngineVersionString(): String {
        return initializedProvider.callback.getWebEngineVersionString()
    }
}

fun WebEngine.isGecko(): Boolean {
    return this.getWebEngineName() == Config.ENGINE_GECKO_VIEW
}