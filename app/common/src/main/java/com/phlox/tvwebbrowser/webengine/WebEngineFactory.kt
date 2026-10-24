package com.phlox.tvwebbrowser.webengine

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.BrowserTab

/**
 * Settings that only some engines support: the settings screen shows them only when the engine does.
 */
enum class EngineSpecificSetting {
    /** Darkening of pages that have no dark theme while the dark UI mode is on */
    ALGORITHMIC_DARKENING
}

interface WebEngineProviderCallback {
    /** [webViewContainer] is where engines put their views (see [WebEngine.onAttachToWindow]) */
    suspend fun initialize(context: Context, webViewContainer: ViewGroup)
    fun createWebEngine(tab: BrowserTab): WebEngine
    suspend fun clearCache(ctx: Context)
    fun onThemeSettingUpdated(value: Config.Theme)
    fun getWebEngineVersionString(): String

    /** Called in the incognito process before the engine is initialized */
    fun prepareIncognitoProcess() {}

    /** Leaving the incognito mode, before its tabs are closed */
    suspend fun clearIncognitoBrowsingData(context: Context) {}

    /** Leaving the incognito mode, after its tabs are closed */
    suspend fun deleteIncognitoData() {}

    /** The engine could not create a view for a tab (for example the system WebView is broken) */
    fun onViewCreationFailed(activity: Activity, error: Throwable) {}

    fun supportsSetting(setting: EngineSpecificSetting): Boolean = false

    /** The engine can clear the data of one site ([clearSiteData]) */
    fun supportsSiteDataClearing(): Boolean = false

    /**
     * Removes the cookies (the user is signed out), the stored data (local storage, IndexedDB and the like)
     * and the cache of the site of [host]. The browser keeps the settings and the history of the site.
     */
    suspend fun clearSiteData(context: Context, host: String) {}
}

data class WebEngineProvider(
    val name: String,
    val callback: WebEngineProviderCallback
)

/**
 * The web engine of the app. Each variant of the app has one engine, which registers itself here
 * (see `TVBro.initWebEngineStuff`).
 */
object WebEngineFactory {
    const val TAG = "WebEngineFactory"
    private var registeredProvider: WebEngineProvider? = null
    private lateinit var initializedProvider: WebEngineProvider

    /** Set by the app in the browser processes; null blocks nothing */
    @Volatile
    var adBlocker: EngineAdBlocker? = null

    fun registerProvider(provider: WebEngineProvider) {
        val registered = registeredProvider
        check(registered == null || registered.name == provider.name) {
            "Web engine ${registered?.name} is registered already, can not register ${provider.name}"
        }
        registeredProvider = provider
    }

    @UiThread
    suspend fun initialize(context: Context, webViewContainer: ViewGroup) {
        val provider = registeredProvider ?: throw IllegalStateException("No web engine is registered")
        provider.callback.initialize(context, webViewContainer)
        initializedProvider = provider
    }

    /** Makes [createWebEngine] use the [provider] without [initialize] */
    @VisibleForTesting
    fun initializeForTests(provider: WebEngineProvider) {
        initializedProvider = provider
    }

    /** The provider of the engine, available before [initialize] */
    private fun selectedProvider(): WebEngineProvider? {
        if (this::initializedProvider.isInitialized) return initializedProvider
        return registeredProvider
    }

    fun prepareIncognitoProcess() {
        selectedProvider()?.callback?.prepareIncognitoProcess()
    }

    suspend fun clearIncognitoBrowsingData(context: Context) {
        selectedProvider()?.callback?.clearIncognitoBrowsingData(context)
    }

    suspend fun deleteIncognitoData() {
        selectedProvider()?.callback?.deleteIncognitoData()
    }

    fun onViewCreationFailed(activity: Activity, error: Throwable) {
        selectedProvider()?.callback?.onViewCreationFailed(activity, error)
    }

    /** Whether the engine supports [setting] */
    fun supportsSetting(setting: EngineSpecificSetting): Boolean =
        selectedProvider()?.callback?.supportsSetting(setting) ?: false

    fun createWebEngine(tab: BrowserTab): WebEngine {
        return initializedProvider.callback.createWebEngine(tab)
    }

    suspend fun clearCache(ctx: Context) {
        initializedProvider.callback.clearCache(ctx)
    }

    fun supportsSiteDataClearing(): Boolean = selectedProvider()?.callback?.supportsSiteDataClearing() ?: false

    suspend fun clearSiteData(context: Context, host: String) {
        initializedProvider.callback.clearSiteData(context, host)
    }

    fun onThemeSettingUpdated(value: Config.Theme) {
        initializedProvider.callback.onThemeSettingUpdated(value)
    }

    fun getWebEngineVersionString(): String {
        return initializedProvider.callback.getWebEngineVersionString()
    }
}
