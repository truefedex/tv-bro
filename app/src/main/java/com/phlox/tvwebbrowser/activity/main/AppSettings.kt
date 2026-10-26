package com.phlox.tvwebbrowser.activity.main

import android.content.Intent
import android.media.tv.TvContract
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
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
import com.phlox.tvwebbrowser.browser.ProxySetup
import com.phlox.tvwebbrowser.channels.ChannelKind
import com.phlox.tvwebbrowser.channels.HomeChannels
import com.phlox.tvwebbrowser.data.FavoritesRepository
import com.phlox.tvwebbrowser.browser.adblock.AdBlockService
import com.phlox.tvwebbrowser.browser.adblock.FilterListCatalog
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.singleton.shortcuts.KeyBinding
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.DownloadFolder
import com.phlox.tvwebbrowser.utils.launchForResult
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.webengine.EngineSpecificSetting
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The settings of the app for the settings screens: [Config], the [SettingsModel] and the models of ad
 * blocking and updates. The checks of the engine and the build are made here.
 */
class AppSettingsStore(
    private val activity: AppCompatActivity,
    private val settingsModel: SettingsModel,
    private val controller: BrowserController
) : SettingsNavigator.Store {
    companion object {
        private const val TAG = "AppSettingsStore"
        private const val BROWSABLE_CHECK_DELAY = 1000L
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
    private val adblock = TVBro.container.adBlock
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

    override val supportsHomeChannels: Boolean get() = HomeChannels.isSupported(activity)
    override var channelBookmarks: Boolean
        get() = config.channelBookmarks
        set(value) {
            config.channelBookmarks = value
            applyChannel(ChannelKind.BOOKMARKS, value)
        }
    override val channelBookmarksSource: Config.ChannelBookmarksSource get() = config.channelBookmarksSource
    override val channelBookmarksFolder: Long get() = config.channelBookmarksFolder

    override fun setChannelBookmarks(source: Config.ChannelBookmarksSource, folder: Long) {
        config.channelBookmarksSource = source
        config.channelBookmarksFolder = folder
        applyChannel(ChannelKind.BOOKMARKS, config.channelBookmarks)
    }

    override suspend fun bookmarkFolders(): List<SettingsNavigator.BookmarkFolder> {
        val favorites = TVBro.container.data.favorites
        val result = ArrayList<SettingsNavigator.BookmarkFolder>()
        val visited = HashSet<Long>()
        suspend fun walk(folder: Long, path: String?) {
            if (!visited.add(folder)) return
            for (child in favorites.children(folder)) {
                if (!child.isFolder) continue
                val childPath = listOfNotNull(path, child.title ?: "").joinToString(" / ")
                result += SettingsNavigator.BookmarkFolder(child.id, childPath)
                walk(child.id, childPath)
            }
        }
        walk(FavoritesRepository.ROOT, null)
        return result
    }

    override var channelTabs: Boolean
        get() = config.channelTabs
        set(value) {
            config.channelTabs = value
            applyChannel(ChannelKind.TABS, value)
        }
    override var channelDownloads: Boolean
        get() = config.channelDownloads
        set(value) {
            config.channelDownloads = value
            applyChannel(ChannelKind.DOWNLOADS, value)
        }

    /**
     * Creates, fills or deletes the channel at once. A new channel shows on the home screen only once the user
     * lets it (the first one of an app is let by the system itself): the system asks in its own dialog.
     */
    private fun applyChannel(kind: ChannelKind, enabled: Boolean) {
        val context = activity.applicationContext
        TVBro.container.appScope.launch {
            TVBro.container.tabWrites.awaitPending()
            val channelId = HomeChannels.sync(context, listOf(kind))[kind] ?: return@launch
            if (!enabled) return@launch
            //the system lets the first channel of an app by itself, a moment after it is created
            delay(BROWSABLE_CHECK_DELAY)
            if (HomeChannels.isBrowsable(context, kind) != false) return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val intent = Intent(TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContract.EXTRA_CHANNEL_ID, channelId)
            try {
                activity.launchForResult(ActivityResultContracts.StartActivityForResult(), intent) {}
            } catch (e: Exception) {
                Log.w(TAG, "No dialog to show the channel of ${kind.id}", e)
            }
        }
    }

    override var defaultDesktopView: Boolean?
        get() = config.defaultDesktopView
        set(value) {
            config.defaultDesktopView = value
            controller.onDefaultSiteViewChanged()
        }
    override var allowAutoplayMedia: Boolean by config::allowAutoplayMedia
    override var adBlockEnabled: Boolean by config::adBlockEnabled
    override val supportsTrackingProtection: Boolean
        get() = WebEngineFactory.supportsSetting(EngineSpecificSetting.TRACKING_PROTECTION)
    /** It applies to the pages loaded after the change */
    override var trackingProtection: Boolean by config::trackingProtection
    override val filterLists: SettingsNavigator.FilterLists get() = filterListsOf(adblock.status.value)
    override val filterListsChanges: Flow<SettingsNavigator.FilterLists> =
        adblock.status.map(::filterListsOf).distinctUntilChanged()

    override fun setFilterListEnabled(id: String, enabled: Boolean) {
        adblock.setListEnabled(id, enabled)
    }

    override fun setRegionalFilterList(id: String?) {
        adblock.setRegionalList(id)
    }

    override fun setCustomFilterList(url: String) {
        adblock.setCustomUrl(url)
    }

    override suspend fun updateFilterLists(): Boolean = adblock.update(force = true)

    private fun filterListsOf(status: AdBlockService.Status): SettingsNavigator.FilterLists {
        fun of(list: AdBlockService.ListState) = SettingsNavigator.FilterList(list.id, list.title, list.enabled,
            FilterListCatalog.find(list.id)?.isRegional ?: false, list.filters, list.lastUpdate, list.failed)
        return SettingsNavigator.FilterLists(
            lists = status.lists.filter { it.id != FilterListCatalog.CUSTOM }.map(::of),
            customUrl = status.customUrl,
            custom = status.lists.firstOrNull { it.id == FilterListCatalog.CUSTOM }?.let(::of),
            updating = status.updating, lastUpdate = status.lastUpdate, skipped = status.skipped,
        )
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
    override var matchFrameRate: Boolean
        get() = config.matchFrameRate
        set(value) {
            config.matchFrameRate = value
        }
    override var pageVideoHint: Boolean by config::pageVideoHint

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
    override val downloadFolderPickerAvailable: Boolean by lazy { DownloadFolder.pickerAvailable(AppContext.get()) }
    override val downloadFolderName: String?
        get() = config.downloadFolderUri?.let { config.downloadFolderName ?: it }
    override fun resetDownloadFolder() = DownloadFolder.reset(AppContext.get(), config)

    override val supportsProxy: Boolean
        get() = WebEngineFactory.supportsSetting(EngineSpecificSetting.PROXY)
    override var proxyEnabled: Boolean
        get() = config.proxyEnabled
        set(value) {
            config.proxyEnabled = value
            applyProxy()
        }
    override var proxyType: ProxySettings.Type
        get() = config.proxyType
        set(value) {
            config.proxyType = value
            applyProxy()
        }
    override var proxyHost: String
        get() = config.proxyHost
        set(value) {
            config.proxyHost = value
            applyProxy()
        }
    override var proxyPort: Int
        get() = config.proxyPort
        set(value) {
            config.proxyPort = value
            applyProxy()
        }
    override var proxyUsername: String
        get() = config.proxyUsername
        set(value) {
            config.proxyUsername = value
            applyProxy()
        }
    override var proxyPassword: String
        get() = config.proxyPassword
        set(value) {
            config.proxyPassword = value
            applyProxy()
        }
    override val proxy: ProxySettings? get() = config.proxy

    /**
     * The app, the engine and the current page go through the proxy at once; after the other settings changed
     * with it (an address with a port), so that it is applied once
     */
    private fun applyProxy() {
        TVBro.container.appScope.launch(Dispatchers.Main) { ProxySetup.apply(config, controller) }
    }

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

    override fun pickFromList(title: String, options: List<String>, checked: Int, onChosen: (Int) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                onChosen(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun pickDownloadFolder(onDone: () -> Unit) {
        val config = AppContext.provideConfig()
        //the picker starts at the chosen folder: it takes a document uri
        val initial = DownloadFolder.chosen(config)?.let { runCatching { DownloadFolder.folderDocument(it) }.getOrNull() }
        activity.launchForResult(ActivityResultContracts.OpenDocumentTree(), initial) { tree ->
            if (tree != null && !DownloadFolder.set(activity, config, tree)) {
                showMessage(R.drawable.ic_warning, activity.getString(R.string.settings_download_folder_failed))
            }
            onDone()
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
