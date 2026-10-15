package com.phlox.tvwebbrowser.webengine

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.BrowserTab

/**
 * Settings that only some engines support: the settings screen shows them only when the selected engine does.
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
}

data class WebEngineProvider(
    val name: String,
    val callback: WebEngineProviderCallback
)

/**
 * Web engine that is not present yet but can be installed on demand
 * (for example, a dynamic feature module delivered by Google Play).
 */
interface WebEngineInstaller {
    val name: String

    /**
     * Installs the engine. On success either the engine registers its [WebEngineProvider], or
     * the implementation restarts the app and never returns.
     * May show its own UI over the [context] activity.
     * @return false if the engine was not installed (error, or the user gave up)
     */
    @UiThread
    suspend fun install(context: Context): Boolean
}

object WebEngineFactory {
    const val TAG = "WebEngineFactory"
    private val engineProviders = mutableListOf<WebEngineProvider>()
    private val engineInstallers = mutableListOf<WebEngineInstaller>()
    private lateinit var initializedProvider: WebEngineProvider

    fun registerProvider(provider: WebEngineProvider) {
        engineProviders.add(provider)
    }

    fun registerInstaller(installer: WebEngineInstaller) {
        engineInstallers.add(installer)
    }

    fun getProviders(): List<WebEngineProvider> {
        return engineProviders
    }

    /**
     * Names of engines the user can choose: registered ones and ones that can be installed.
     */
    fun getAvailableEngines(): List<String> {
        return (engineProviders.map { it.name } + engineInstallers.map { it.name }).distinct()
    }

    fun isEngineInstalled(name: String): Boolean {
        return engineProviders.any { it.name == name }
    }

    @UiThread
    suspend fun initialize(context: Context, webViewContainer: ViewGroup) {
        val config = AppContext.provideConfig()
        var webEngineProvider = engineProviders.find { it.name == config.webEngine }
        if (webEngineProvider == null) {
            val installer = engineInstallers.find { it.name == config.webEngine }
            if (installer != null && installer.install(context)) {
                webEngineProvider = engineProviders.find { it.name == config.webEngine }
                if (webEngineProvider != null) {
                    engineInstallers.remove(installer)
                }
            }
        }
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

    /** Makes [createWebEngine] use the [provider] without [initialize] */
    @VisibleForTesting
    fun initializeForTests(provider: WebEngineProvider) {
        initializedProvider = provider
    }

    /**
     * The provider of the engine chosen in the settings (or the first registered one, like [initialize] does).
     * Available before [initialize].
     */
    private fun selectedProvider(): WebEngineProvider? {
        if (this::initializedProvider.isInitialized) return initializedProvider
        val config = AppContext.provideConfig()
        return engineProviders.find { it.name == config.webEngine } ?: engineProviders.firstOrNull()
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

    /** Whether the engine chosen in the settings supports [setting] */
    fun supportsSetting(setting: EngineSpecificSetting): Boolean =
        selectedProvider()?.callback?.supportsSetting(setting) ?: false

    fun createWebEngine(tab: BrowserTab): WebEngine {
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
