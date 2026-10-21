package com.phlox.tvwebbrowser.activity.main

import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.dialogs.ShortcutKeyDialog
import com.phlox.tvwebbrowser.browser.BrowserController
import com.phlox.tvwebbrowser.singleton.shortcuts.KeyBinding
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.webengine.EngineSpecificSetting
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * The settings of the app for the settings screens: [Config], the [SettingsModel] and the models of ad
 * blocking and updates. The checks of the engine and the build are made here.
 */
class AppSettingsStore(
    activity: AppCompatActivity,
    private val settingsModel: SettingsModel,
    private val controller: BrowserController
) : SettingsNavigator.Store {
    companion object {
        private var startup: SettingsNavigator.StartupValues? = null

        /** Remembers the values the process starts with, before anything changes them (MainActivity.onCreate) */
        fun rememberStartupValues(config: Config) {
            if (startup != null) return
            startup = valuesOf(config)
        }

        private fun valuesOf(config: Config) = SettingsNavigator.StartupValues(
            config.interfaceMode, config.theme.value, config.webviewUseAlgorithmicDarkeningWithDarkUiMode, config.webEngineDebug)
    }

    private val config = AppContext.provideConfig()
    private val adblock = ActiveModelsRepository.get(AdblockModel::class, activity)
    private val autoUpdate = ActiveModelsRepository.get(AutoUpdateModel::class, activity)
    private val shortcuts = ShortcutMgr.getInstance(activity)

    override val startupValues: SettingsNavigator.StartupValues
        get() = startup ?: valuesOf(config).also { startup = it }

    override var searchEngineUrl: String
        get() = config.searchEngineURL.value
        set(value) = settingsModel.setSearchEngineURL(value)
    override val homePageMode: Config.HomePageMode get() = settingsModel.homePageMode
    override val homePageUrl: String get() = settingsModel.homePage
    override val homePageLinksMode: Config.HomePageLinksMode get() = settingsModel.homePageLinksMode

    override fun setHomePage(mode: Config.HomePageMode, customUrl: String?, linksMode: Config.HomePageLinksMode) {
        settingsModel.setHomePageProperties(mode, customUrl, linksMode)
        //the home page shows the links of the new mode
        TVBro.container.appScope.launch { controller.homePageLinks.load() }
    }

    override var defaultDesktopView: Boolean?
        get() = config.defaultDesktopView
        set(value) {
            config.defaultDesktopView = value
            controller.onDefaultSiteViewChanged()
        }
    override var allowAutoplayMedia: Boolean by config::allowAutoplayMedia
    override var adBlockEnabled: Boolean by config::adBlockEnabled
    override var filterListUrl: String
        get() = config.adBlockListURL.value
        set(value) {
            config.adBlockListURL.value = value.trim().ifEmpty { Config.DEFAULT_ADBLOCK_LIST_URL }
        }
    override val filterListLastUpdate: Long get() = config.adBlockListLastUpdate
    override val filterListLoading: Flow<Boolean> = callbackFlow {
        val observer: (Boolean) -> Unit = { trySend(it) }
        adblock.clientLoading.subscribe(observer)
        awaitClose { adblock.clientLoading.unsubscribe(observer) }
    }

    override fun updateFilterList() {
        adblock.loadAdBlockList(true)
    }

    override var interfaceMode: Config.InterfaceMode by config::interfaceMode
    /** Its observer sets the night mode and tells the engine */
    override var theme: Config.Theme
        get() = config.theme.value
        set(value) {
            config.theme.value = value
        }
    override val supportsDarkening: Boolean
        get() = WebEngineFactory.supportsSetting(EngineSpecificSetting.ALGORITHMIC_DARKENING)
    override var darkening: Boolean by config::webviewUseAlgorithmicDarkeningWithDarkUiMode
    /** Its observer sets the window flag */
    override var keepScreenOn: Boolean
        get() = settingsModel.keepScreenOn.value
        set(value) {
            settingsModel.keepScreenOn.value = value
        }

    override var joystickNavigation: Boolean
        get() = !config.disableMotionAxesDpadNavigation
        set(value) {
            config.disableMotionAxesDpadNavigation = !value
        }
    override var cursorSpeed: Int by config::cursorMaxSpeedPercent
    override var cursorAcceleration: Int by config::cursorAccelerationPercent
    override fun bindingOf(shortcut: Shortcut): KeyBinding = shortcuts.bindingOf(shortcut)

    override suspend fun clearCache() = WebEngineFactory.clearCache(AppContext.get())
    override var webEngineDebug: Boolean by config::webEngineDebug

    override val versionName: String get() = BuildConfig.VERSION_NAME
    override val buildName: String get() = BuildConfig.FLAVOR_appstore
    override val engineVersion: String get() = WebEngineFactory.getWebEngineVersionString()
    override val builtInUpdates: Boolean get() = BuildConfig.BUILT_IN_AUTO_UPDATE
    override var autoCheckUpdates: Boolean
        get() = autoUpdate.needAutoCheckUpdates
        set(value) = autoUpdate.saveAutoCheckUpdates(value)
    override var updateChannel: String by config::updateChannel

    override suspend fun checkUpdates(force: Boolean): SettingsNavigator.Updates? {
        autoUpdate.checkUpdate(force) {}.join()
        val result = autoUpdate.updateChecker.versionCheckResult ?: return null
        return SettingsNavigator.Updates(result.availableChannels.toList(),
            result.latestVersionName.takeIf { autoUpdate.updateChecker.hasUpdate() })
    }
}

/**
 * What both shells give their settings screen: the dialogs, the key capture, links in a new tab, the update
 * and the restart.
 * @param showMessage the shell's own way to tell something
 */
class SettingsShellCallback(
    private val host: BrowserShell.Host,
    private val showMessage: (icon: Int, message: String) -> Unit
) : SettingsNavigator.ShellCallback {
    private val activity get() = host.activity

    override val store: SettingsNavigator.Store by lazy {
        AppSettingsStore(activity, host.settingsModel, host.controller)
    }

    override fun showMessage(@DrawableRes icon: Int, message: String) = showMessage.invoke(icon, message)

    override fun confirm(title: String?, message: String, positive: String, onResult: (Boolean) -> Unit) {
        var answered = false
        val answer = { yes: Boolean ->
            if (!answered) {
                answered = true
                onResult(yes)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> answer(true) }
            .setNegativeButton(R.string.cancel) { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) }
            .show()
    }

    override fun captureKey(shortcut: Shortcut, onDone: () -> Unit) {
        ShortcutKeyDialog(activity, shortcut).apply {
            setOnDismissListener { onDone() }
            show()
        }
    }

    override fun open(url: String) {
        host.openInNewTab(url, host.controller.tabsStates.size)
    }

    /** The screen is closed already; the dialog of the update offers to download it */
    override fun startUpdate() {
        val autoUpdate = ActiveModelsRepository.get(AutoUpdateModel::class, activity)
        val mainActivity = activity as? MainActivity ?: return
        if (autoUpdate.updateChecker.hasUpdate()) {
            autoUpdate.showUpdateDialogIfNeeded(mainActivity, true)
        }
    }

    override fun restart() {
        TVBro.exitProcessAfterMainActivityFinish(restartMainActivity = true)
        activity.finish()
    }
}
