package com.phlox.tvwebbrowser.webengine

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.net.AppProxy
import com.phlox.tvwebbrowser.net.ProxySettings

/**
 * Settings that only some engines support: the settings screen shows them only when the engine does.
 */
enum class EngineSpecificSetting {
    /** Darkening of pages that have no dark theme while the dark UI mode is on */
    ALGORITHMIC_DARKENING,
    /** The engine's own blocking of trackers ([Config.trackingProtection]) */
    TRACKING_PROTECTION,
    /** Going through a proxy ([WebEngineProviderCallback.applyProxy]) */
    PROXY
}

interface WebEngineProviderCallback {
    /**
     * [webViewContainer] is where engines put their views (see [WebEngine.onAttachToWindow]). An engine that
     * supports [EngineSpecificSetting.PROXY] goes through the proxy of [AppProxy.settings] before it returns,
     * so that nothing loads before.
     */
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

    /**
     * The engine can keep the data of the pages of the kids profile (cookies, storage, cache) apart from
     * those of the main profile, on this device
     */
    fun supportsKidsProfile(): Boolean = false

    /** Called in the process of the kids profile ([com.phlox.tvwebbrowser.AppContext.kidsProfile]) before the engine is initialized */
    fun prepareKidsProcess() {}

    /**
     * Deletes the data of the pages of the kids profile (cookies, storage, cache), from another process than
     * the one of the kids profile, which is not running then
     */
    suspend fun deleteKidsData(context: Context) {}

    /** The process is about to be killed: what the engine keeps in memory for a while (cookies) goes to the disk */
    fun flushData() {}

    /** The engine could not create a view for a tab (for example the system WebView is broken) */
    fun onViewCreationFailed(activity: Activity, error: Throwable) {}

    fun supportsSetting(setting: EngineSpecificSetting): Boolean = false

    /** The engine can clear the data of one site ([clearSiteData]) */
    fun supportsSiteDataClearing(): Boolean = false

    /**
     * Removes the [kinds] of data of the site of [host]: its cookies (the user is signed out), its stored data
     * (local storage, IndexedDB and the like) and its cache; the others stay. The browser keeps the settings
     * and the history of the site.
     */
    suspend fun clearSiteData(context: Context, host: String, kinds: Set<SiteDataKind>) {}

    /**
     * Makes all pages of the engine go through [proxy] (directly when null), except the local addresses of
     * [ProxySettings.BYPASS_DOMAINS] and [ProxySettings.BYPASS_NETWORKS]; returns once it is applied.
     * Pages never fall back to a direct connection when the proxy fails.
     */
    suspend fun applyProxy(proxy: ProxySettings?) {}
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

    /** Set by the app in the browser processes; null leaves all pages at 100% */
    @Volatile
    var pageZooms: EnginePageZooms? = null

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

    fun supportsKidsProfile(): Boolean = selectedProvider()?.callback?.supportsKidsProfile() == true

    fun prepareKidsProcess() {
        selectedProvider()?.callback?.prepareKidsProcess()
    }

    fun flushData() {
        selectedProvider()?.callback?.flushData()
    }

    suspend fun deleteKidsData(context: Context) {
        selectedProvider()?.callback?.deleteKidsData(context)
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

    suspend fun clearSiteData(context: Context, host: String, kinds: Set<SiteDataKind>) {
        if (kinds.isEmpty()) return
        initializedProvider.callback.clearSiteData(context, host, kinds)
    }

    /** See [WebEngineProviderCallback.applyProxy]; does nothing before [initialize] (it applies the proxy itself) */
    suspend fun applyProxy(proxy: ProxySettings?) {
        if (!this::initializedProvider.isInitialized) return
        initializedProvider.callback.applyProxy(proxy)
    }

    fun onThemeSettingUpdated(value: Config.Theme) {
        initializedProvider.callback.onThemeSettingUpdated(value)
    }

    fun getWebEngineVersionString(): String {
        return initializedProvider.callback.getWebEngineVersionString()
    }
}
