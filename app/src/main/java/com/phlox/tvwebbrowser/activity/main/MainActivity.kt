package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.app.ActivityManager
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.AppProcesses
import com.phlox.tvwebbrowser.send.LegacySync
import com.phlox.tvwebbrowser.send.SendSettings
import com.phlox.tvwebbrowser.channels.HomeChannels
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.activity.KidsProfileMainActivity
import com.phlox.tvwebbrowser.profile.PinDialog
import com.phlox.tvwebbrowser.profile.ProfileLock
import com.phlox.tvwebbrowser.profile.ProfilePolicy
import com.phlox.tvwebbrowser.utils.ActivityResults
import com.phlox.tvwebbrowser.browser.BrowserCommands
import com.phlox.tvwebbrowser.browser.BrowserController
import com.phlox.tvwebbrowser.browser.BrowserUiEvent
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.browser.DownloadsModel
import com.phlox.tvwebbrowser.browser.UrlInputResolver
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.BackNavigationEventsAdapter
import com.phlox.tvwebbrowser.utils.JoystickDpadRouter
import com.phlox.tvwebbrowser.utils.OverlayBackInvokedCallback
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.VoiceSearchHelper
import com.phlox.tvwebbrowser.utils.launchForResult
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.sameDay
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import com.phlox.tvwebbrowser.webengine.SiteView
import com.phlox.tvwebbrowser.player.PictureInPicturePlayback
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.webengine.SiteDataKind
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.system.exitProcess

/**
 * The browser screen. It owns what does not depend on the look of the screen (the browser core,
 * intents, navigation, incognito, downloads, keys and Back) and leaves the look to a [BrowserShell].
 */
open class MainActivity : AppCompatActivity(), BrowserShell.Host {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val KEY_PROCESS_ID_TO_KILL = "proc_id_to_kill"
        /**
         * When the app itself started the activity (it changed the process, or restarts), by
         * [SystemClock.elapsedRealtime]: the user was just there, so a fresh one does not lock the main profile
         */
        private const val KEY_SESSION_TIME = "session_time"
        private const val SESSION_TIME_VALID_MS = 60_000L
        /** The start of this process was looked at ([ProfileLock.onMainColdStart]) */
        private var coldStartHandled = false
        /** Browser activities of this process that show the browser and are not destroyed yet */
        private var liveActivities = 0
        /** This process has shown the browser in an activity already */
        private var hostedBrowser = false
        /** The browser is going to the activity of another process: this process ends */
        private var switchingProcess = false
        /** An engine was initialized in this process: it may hold data that are not on the disk yet */
        private var engineUsed = false

        private fun activityClassOf(space: BrowserSpace): Class<out MainActivity> = when (space) {
            BrowserSpace.MAIN -> MainActivity::class.java
            BrowserSpace.INCOGNITO -> IncognitoModeMainActivity::class.java
            BrowserSpace.KIDS -> KidsProfileMainActivity::class.java
        }

        /** Marks the [intent] as one the app starts its browser activity with (see [KEY_SESSION_TIME]) */
        fun markSameSession(intent: Intent): Intent = intent.putExtra(KEY_SESSION_TIME, SystemClock.elapsedRealtime())

        private fun isSameSession(intent: Intent?): Boolean {
            val time = intent?.getLongExtra(KEY_SESSION_TIME, -1L) ?: -1L
            //an intent restored after a reboot holds a time of the boot before
            return time >= 0 && SystemClock.elapsedRealtime() - time in 0..SESSION_TIME_VALID_MS
        }
        /** Open the downloads (the notification of the download service) */
        const val KEY_SHOW_DOWNLOADS = "show_downloads"
        /** With [KEY_SHOW_DOWNLOADS]: the download to focus */
        const val KEY_DOWNLOAD_ID = "download_id"
        /** The picture-in-picture window of the built-in player was expanded: its video goes on in the browser */
        const val KEY_SHOW_PIP_PLAYER = "show_pip_player"
        /** A tile of the tabs channel of the TV home screen: the tab to show (its url is the data) */
        private const val EXTRA_TAB_ID = "channel_tab_id"
        /**
         * A tile of the bookmarks channel: a tab that shows the url already (engines may add a slash at its end)
         * is shown instead of a new one
         */
        private const val EXTRA_REUSE_TAB = "channel_reuse_tab"

        fun showDownloadsIntent(context: Context, focusId: Long? = null): Intent =
            Intent(context, MainActivity::class.java).putExtra(KEY_SHOW_DOWNLOADS, true).apply {
                if (focusId != null) putExtra(KEY_DOWNLOAD_ID, focusId)
            }

        fun channelBookmarkIntent(context: Context, url: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url), context, MainActivity::class.java).putExtra(EXTRA_REUSE_TAB, true)

        fun channelTabIntent(context: Context, tabId: Long, url: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url), context, MainActivity::class.java).putExtra(EXTRA_TAB_ID, tabId)

        /**
         * The intent that starts the browser activity of another process (that of the [space]) and makes it
         * kill this one. [source] (an intent that came to the activity of the wrong process: a link to
         * open) goes there whole, so it is handled as if it had come there. With [clearTask] the activity is
         * always a new one, alone in its task; without, one that is there already (the activities are
         * `singleTask`) only gets the intent ([onNewIntent]) and comes to the front as it is.
         */
        internal fun switchProcessIntent(context: Context, space: BrowserSpace, source: Intent?, pidToKill: Int,
                                         clearTask: Boolean = true): Intent {
            val intent = Intent(context, activityClassOf(space))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (clearTask) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (source != null) {
                intent.action = source.action
                //setData and setType clear each other
                intent.setDataAndType(source.data, source.type)
                source.extras?.let { intent.putExtras(it) }
            }
            //after the extras: an intent restored from the history may hold the pid of a process gone
            intent.putExtra(KEY_PROCESS_ID_TO_KILL, pidToKill)
            return markSameSession(intent)
        }
    }

    private lateinit var uiMode: UiMode
    private lateinit var shell: BrowserShell
    override lateinit var controller: BrowserController
    override lateinit var settingsModel: SettingsModel
    private lateinit var autoUpdateModel: AutoUpdateModel
    private lateinit var uiHandler: Handler
    private var isFullscreen: Boolean = false
        set(value) {
            field = value
            fullscreenBackCallback.isEnabled = value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val overlayBack = fullscreenOverlayBack ?: OverlayBackInvokedCallback(onBackInvokedDispatcher) {
                    backNavigationEventsAdapter.dispatchSystemBackNavigationEvent()
                }.also { fullscreenOverlayBack = it }
                overlayBack.setRegistered(value)
            }
        }
    /** Back of a fullscreen page goes to the back callbacks of the screen, not to the fullscreen view of WebView */
    private var fullscreenOverlayBack: OverlayBackInvokedCallback? = null
    protected val config = AppContext.provideConfig()
    /** Where this activity shows the browser; each space has an activity in a process of its own */
    protected open val space: BrowserSpace get() = BrowserSpace.MAIN
    private val profileLock by lazy { ProfileLock.get(this) }
    private val policy = ProfilePolicy.current
    /** This activity is one of [liveActivities] */
    private var countedAsLive = false
    private val voiceSearchHelper = VoiceSearchHelper(this) { icon, message -> shell.showMessage(icon, message) }
    private val downloadLauncher = DownloadLauncher(this,
        onFolderUnavailable = { shell.showMessage(R.drawable.ic_warning, getString(R.string.download_folder_unavailable)) }
    ) { shell.onDownloadStarted(it) }
    var openUrlInExternalAppDialog: AlertDialog? = null
    private var tabsLoaded = false
    /** Url to open that arrived before the tabs were loaded */
    private var pendingIntent: Intent? = null

    override val activity: AppCompatActivity get() = this

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //the kids profile is of the TV interface only
        uiMode = UiModeSelector.select(this, config.interfaceMode)
        if (space != BrowserSpace.KIDS && !coldStartHandled) {
            coldStartHandled = true
            //the browser of the incognito mode runs (a link of another app came while it was shown): the main
            //profile is open, and leaving the screen locks it as usual
            if (!isSameSession(intent) && !AppProcesses.isRunning(this, AppProcesses.INCOGNITO_SUFFIX)) {
                profileLock.onMainColdStart()
            }
        }
        val wanted = wantedSpace()
        Log.d(TAG, "onCreate space: $space, wanted: $wanted")
        if (wanted != space) {
            //this activity only passes the intent on (the launcher, a link of another app): the browser of
            //the other process, if it is there, stays as it is and comes to the front. Else the task is
            //cleared: it may hold the browser of a space that is not to be shown (the incognito mode the
            //main profile was locked in), which Back in the new one would show again.
            //Finished first: switchProcess may end this process at once (no tab writes are pending), and the
            //record of an activity that did not finish stays in the task, under the one this one starts.
            finish()
            switchProcess(wanted, intent, clearTask = !hasBrowserActivity(wanted))
            return
        }
        //only of an intent the app has just sent: one restored later holds the pid of a process long gone,
        //which may be another process of the app by now
        val pidToKill = if (isSameSession(intent)) intent?.getIntExtra(KEY_PROCESS_ID_TO_KILL, -1) ?: -1 else -1
        if (pidToKill != -1 && pidToKill != Process.myPid()) {
            Process.killProcess(pidToKill)
        }
        if (liveActivities > 0 || (hostedBrowser && space != BrowserSpace.MAIN)) {
            //the activity of the main process sent the browser here (a link of another app, the launcher)
            //while this process has, or just had, its browser activity: that one is destroyed around the
            //time this one is created, and the browser core and the engine views are still its own. So
            //the browser starts again in a new process, as on every other switch. The main process shows
            //the browser again in a new activity only after the old one is gone (the launcher after Back).
            Log.d(TAG, "onCreate: the process has shown the browser already, starting again")
            finish()
            switchProcess(space, intent)
            return
        }
        liveActivities++
        countedAsLive = true
        hostedBrowser = true

        when (space) {
            BrowserSpace.INCOGNITO -> WebEngineFactory.prepareIncognitoProcess()
            BrowserSpace.KIDS -> WebEngineFactory.prepareKidsProcess()
            BrowserSpace.MAIN -> deleteEndedIncognito()
        }
        ActivityResults.reset()
        AppSettingsStore.rememberStartupValues(config)
        lifecycleScope.launch(Dispatchers.IO) { LegacySync.drop(applicationContext, SendSettings(applicationContext, config.prefs)) }
        settingsModel = ActiveModelsRepository.get(SettingsModel::class, this)
        controller = ActiveModelsRepository.get(BrowserController::class, this)
        autoUpdateModel = ActiveModelsRepository.get(AutoUpdateModel::class, this)
        uiHandler = Handler(Looper.getMainLooper())
        shell = when (uiMode) {
            UiMode.TV -> TvShell(this)
            UiMode.PHONE -> PhoneShell(this)
        }
        requestedOrientation = shell.screenOrientation
        setContentView(shell.root)
        controller.viewHost = tabViewHost
        controller.interfaceSiteView = if (uiMode == UiMode.TV) SiteView.TABLET else SiteView.MOBILE

        config.theme.subscribe(this.lifecycle, false) {
            when (it) {
                Config.Theme.BLACK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                Config.Theme.WHITE -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
            WebEngineFactory.onThemeSettingUpdated(it)
        }

        settingsModel.keepScreenOn.subscribe(this.lifecycle) {
            if (it) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        lifecycleScope.launch {
            controller.homePageLinks.links.collect {
                Log.i(TAG, "homePageLinks updated")
                val currentUrl = controller.currentTab.value?.url ?: return@collect
                if (Config.HOME_PAGE_URL == currentUrl) {
                    controller.currentTab.value?.webEngine?.reload()
                }
            }
        }

        lifecycleScope.launch {
            controller.currentTabState.collect { shell.render(it) }
        }
        lifecycleScope.launch {
            ActiveModelsRepository.get(DownloadsModel::class, this@MainActivity).inProgress
                .collect { downloadLauncher.onDownloadsInProgress(it) }
        }
        lifecycleScope.launch {
            controller.uiEvents.collect { onBrowserUiEvent(it) }
        }

        onBackPressedDispatcher.addCallback(onBackPressedCallback)
        shell.registerBackCallbacks(screenBackDispatcher, fullscreenBackCallback)
        //highest priority: Back cancels the voice input in progress
        screenBackDispatcher.addCallback(this, voiceSearchHelper.backCallback)

        loadState()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkStateChanged()
        override fun onLost(network: Network) = onNetworkStateChanged()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            onNetworkStateChanged()

        //called on a ConnectivityManager thread, often with no actual changes
        private fun onNetworkStateChanged() {
            val connected = Utils.isNetworkConnected(this@MainActivity)
            if (connected == networkConnected) return
            networkConnected = connected
            uiHandler.post { controller.currentTab.value?.webEngine?.setNetworkAvailable(connected) }
        }
    }

    @Volatile
    private var networkConnected: Boolean? = null

    private fun updateNetworkAvailability() {
        val connected = Utils.isNetworkConnected(this)
        networkConnected = connected
        controller.currentTab.value?.webEngine?.setNetworkAvailable(connected)
    }

    override fun closeWindow() {
        Log.d(TAG, "closeWindow")
        lifecycleScope.launch {
            if (space == BrowserSpace.INCOGNITO) {
                toggleIncognitoMode(false).join()
            }
            finish()
        }
    }

    override val downloadCommands = object : DownloadsNavigator.Commands {
        /** Repeated and resumed downloads get the cookies and the user agent of the current tab */
        private val engine get() = controller.currentTab.value?.takeIf { it.isWebEngineCreated }?.webEngine

        override fun pause(id: Long) = downloadLauncher.pause(id)

        override fun resume(item: DownloadItem) = downloadLauncher.resume(item, engine?.getCookies(item.url))

        override fun cancel(id: Long) = downloadLauncher.cancel(id)

        override fun downloadAgain(item: DownloadItem) {
            val engine = engine
            downloadLauncher.downloadAgain(item, engine?.userAgentString, engine?.getCookies(item.url))
        }

        override suspend fun remove(ids: List<Long>) = TVBro.container.data.downloads.delete(ids)

        override fun onScreenShown(shown: Boolean) = downloadLauncher.onScreenShown(shown)
    }

    /** Opens the settings of the shell at the [category] (the update dialog opens About) */
    fun showSettings(category: SettingsNavigator.Category? = null) {
        if (!policy.settings) return
        shell.showSettings(category)
    }

    override val kidsProfileEnabled: Boolean get() = kidsProfileAvailable && profileLock.enabled

    override val kidsProfileAvailable: Boolean
        get() = uiMode == UiMode.TV && WebEngineFactory.supportsKidsProfile()

    /** Where the browser is to be now: the kids profile unless its PIN was entered, else incognito or not */
    private fun wantedSpace(): BrowserSpace {
        //another process may have changed the settings (the incognito mode) since this one read them
        getSharedPreferences(TVBro.MAIN_PREFS_NAME, MODE_MULTI_PROCESS)
        if (kidsProfileAvailable && profileLock.kidsActive) {
            endIncognitoForKids()
            return BrowserSpace.KIDS
        }
        //never on in the process of the kids profile: leaving it goes through the main activity
        return if (config.incognitoMode) BrowserSpace.INCOGNITO else BrowserSpace.MAIN
    }

    /**
     * The kids profile takes the screen: an incognito session of the main profile ends, so the PIN opens the
     * main profile and not that session. Its tabs and the data of its pages are deleted by the next browser
     * process of the main profile ([deleteEndedIncognito]): only that one has its database, and the kids
     * profile may come first.
     */
    private fun endIncognitoForKids() {
        if (!config.incognitoMode) return
        config.incognitoEndPending = true
        config.incognitoMode = false
    }

    /** Deletes what an incognito session that the kids profile ended left ([endIncognitoForKids]) */
    private fun deleteEndedIncognito() {
        if (!config.incognitoEndPending) return
        val app = applicationContext
        val container = TVBro.container
        container.tabWrites.enqueue {
            AppProcesses.end(app, AppProcesses.INCOGNITO_SUFFIX)
            val tabs = container.data.tabs.load(incognito = true)
            container.data.tabs.deleteAll(incognito = true)
            withContext(Dispatchers.IO) {
                for (tab in tabs) container.tabFiles.removeFiles(tab.wvStateFileName, tab.thumbnailHash)
            }
            try {
                WebEngineFactory.deleteIncognitoData()
            } catch (e: Exception) {
                Log.e(TAG, "deleteEndedIncognito", e)
            }
            config.incognitoEndPending = false
        }
    }

    /** The browser activity of the [space] is in a task of the app (also when the system ended its process) */
    private fun hasBrowserActivity(space: BrowserSpace): Boolean {
        val name = activityClassOf(space).name
        val tasks = try {
            getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        return tasks.any { task ->
            //a task removed meanwhile throws
            val info = try { task.taskInfo } catch (e: IllegalArgumentException) { null } ?: return@any false
            info.baseActivity?.className == name || info.topActivity?.className == name
        }
    }

    /** Goes to the activity of the space the browser is to be in; @return false if this is the one */
    private fun routeToWantedSpace(): Boolean {
        val wanted = wantedSpace()
        if (wanted == space) return false
        if (!switchingProcess) {
            //the process is killed without pausing the activity
            controller.onActivityPause()
            switchProcess(wanted)
        }
        return true
    }

    override fun switchProfile() {
        if (!kidsProfileEnabled) return
        if (space == BrowserSpace.KIDS) {
            PinDialog.ask(this, getString(R.string.profile_main)) { right ->
                if (right) {
                    profileLock.enterMain()
                    routeToWantedSpace()
                }
            }
        } else {
            profileLock.enterKids()
            routeToWantedSpace()
        }
    }

    override fun toggleSiteAdBlock() {
        val tab = controller.currentTab.value ?: return
        controller.setSiteAdBlock(tab, !controller.currentTabState.value.adBlockEnabled)
    }

    override fun choosePopupBlockLevel() {
        lifecycleScope.launch {
            val tab = controller.currentTab.value ?: return@launch
            val currentHostConfig = controller.loadHostConfig(tab)
            val currentBlockPopupsLevelValue = currentHostConfig?.popupBlockLevel ?: HostConfig.DEFAULT_BLOCK_POPUPS_VALUE
            val hostName = currentHostConfig?.hostName ?: controller.currentTabState.value.host
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.block_popups_s, hostName))
                .setSingleChoiceItems(R.array.popup_blocking_level, currentBlockPopupsLevelValue) { dialog, itemId ->
                    lifecycleScope.launch {
                        controller.changePopupBlockingLevel(itemId, tab)
                        dialog.dismiss()
                    }
                }
                .show()
        }
    }

    override fun chooseSiteView() {
        val tab = controller.currentTab.value ?: return
        val state = controller.currentTabState.value
        if (state.host.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.site_view_s, state.host))
            .setSingleChoiceItems(R.array.site_views, siteViewPosition(state.siteView)) { dialog, position ->
                controller.setSiteView(tab, SITE_VIEWS[position])
                dialog.dismiss()
            }
            .show()
    }

    override fun clearSiteData() {
        val tab = controller.currentTab.value ?: return
        val host = controller.currentTabState.value.host
        if (host.isEmpty()) return
        val kinds = listOf(SiteDataKind.COOKIES, SiteDataKind.STORAGE, SiteDataKind.CACHE)
        val labels = arrayOf<CharSequence>(getString(R.string.clear_site_data_cookies),
            getString(R.string.clear_site_data_storage), getString(R.string.clear_site_data_cache))
        val checked = BooleanArray(kinds.size) { true }
        val builder = AlertDialog.Builder(this)
        //a dialog with a list has no message: what stays goes under the list
        val padding = TypedValue().let {
            builder.context.theme.resolveAttribute(androidx.appcompat.R.attr.dialogPreferredPadding, it, true)
            TypedValue.complexToDimensionPixelSize(it.data, resources.displayMetrics)
        }
        val keeps = TextView(builder.context, null, 0, R.style.Widget_TVBro_Text_Body).apply {
            setText(R.string.clear_site_data_keeps)
            setTextColor(context.themeColor(R.attr.tbColorDsInk))
            setPaddingRelative(padding, padding / 3, padding, 0)
        }
        builder
            .setTitle(getString(R.string.clear_site_data_title, host))
            .setMultiChoiceItems(labels, checked) { d, _, _ ->
                //the dialog keeps [checked] up to date
                (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = checked.any { it }
            }
            .setView(keeps)
            .setPositiveButton(R.string.clear) { _, _ ->
                val chosen = kinds.filterIndexed { i, _ -> checked[i] }.toSet()
                lifecycleScope.launch {
                    try {
                        controller.clearSiteData(tab, chosen)
                    } catch (e: Exception) {
                        Log.e(TAG, "clearSiteData", e)
                        Utils.showToast(this@MainActivity, R.string.clear_site_data_failed)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
        builder.show()
    }

    override fun download(request: DownloadRequest) {
        if (refusedByProfile(policy.downloads)) return
        downloadLauncher.download(request)
    }

    override suspend fun receiveDownload(request: DownloadRequest) =
        if (policy.downloads && policy.sendToTv) downloadLauncher.receive(request) else null

    /** Tells the user that the kids profile does not do it; @return true when it is refused */
    private fun refusedByProfile(allowed: Boolean): Boolean {
        if (allowed) return false
        shell.showMessage(R.drawable.ic_lock, getString(R.string.profile_not_available))
        return true
    }

    override fun copyToClipboard(url: String) = TabUiHost.copyToClipboard(this, url)

    override val commands: BrowserCommands = object : BrowserCommands {
        override fun goBack() {
            if (controller.goBack()) {
                return
            }
            shell.onNoBackHistory()
        }

        override fun goForward() = controller.goForward()

        override fun goHome() = navigate(settingsModel.homePage)

        override fun reload() = controller.reload()

        override fun startVoiceSearch() = initiateVoiceSearch()

        override fun togglePlayback() {
            if (!mediaCommand(MediaCommand.TOGGLE)) controller.togglePlayback()
        }

        override fun stopPlayback() {
            if (!mediaCommand(MediaCommand.STOP)) controller.stopPlayback()
        }

        override fun rewind() {
            if (!mediaCommand(MediaCommand.REWIND)) controller.rewind()
        }

        override fun fastForward() {
            if (!mediaCommand(MediaCommand.FAST_FORWARD)) controller.fastForward()
        }

        /** The built-in player, then its picture-in-picture window over the browser, before the page */
        private fun mediaCommand(command: MediaCommand): Boolean =
            shell.onMediaCommand(command) || PictureInPicturePlayback.mediaCommand(command)

        override fun findInPage() = shell.showFindInPage()

        /**
         * The tabs stay while a video is in fullscreen or in the player: the channel keys of a remote are
         * pressed by habit while watching
         */
        private fun tabsLocked() = isFullscreen || shell.takesDpad()

        override fun newTab() {
            if (tabsLocked()) return
            openInNewTab(settingsModel.homePage, controller.tabsStates.size)
        }

        override fun closeTab() {
            if (tabsLocked()) return
            val tab = controller.currentTab.value ?: return
            shell.showPage()
            controller.closeTab(tab)
        }

        override fun switchTab(offset: Int) {
            if (tabsLocked()) return
            val tabs = controller.tabsStates
            val index = tabs.indexOf(controller.currentTab.value)
            if (index == -1 || tabs.size < 2) return
            shell.showPage()
            controller.changeTab(tabs[Math.floorMod(index + offset, tabs.size)])
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        //a long press of B held while the activity closes must not go back in it later
        backNavigationEventsAdapter.consumeCurrentPress()
        //here properties can be uninitialized in case of wrong activity for incognito mode
        //detection and force activity restart in onCreate()
        if (::controller.isInitialized) {
            controller.onDetachActivity(tabViewHost)
        }
        if (countedAsLive) {
            countedAsLive = false
            liveActivities--
        }
        super.onDestroy()
    }

    @SuppressLint("MissingSuperCall")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(KEY_SHOW_DOWNLOADS, false) && policy.downloads) {
            shell.showAllDownloads(intent.getLongExtra(KEY_DOWNLOAD_ID, 0L).takeIf { it > 0L })
        }
        if (intent.getBooleanExtra(KEY_SHOW_PIP_PLAYER, false) && policy.pictureInPicture) showPictureInPicturePlayer()
        if (intent.data != null) {
            if (tabsLoaded) {
                handleIntent(intent)
            } else {
                //the web engine is not initialized and the saved tabs are not loaded yet
                pendingIntent = intent
            }
        }
    }

    /** The video of the expanded picture-in-picture window goes on in the player of the shell, else it ends */
    private fun showPictureInPicturePlayer() {
        if (!shell.showPictureInPicturePlayer()) PictureInPicturePlayback.end()
    }

    private fun loadState() = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "loadState")
        engineUsed = true
        WebEngineFactory.initialize(this@MainActivity, shell.tabsContainer)

        shell.showBusy(true)
        controller.loadState().join()

        if (!isActive) {
            return@launch
        }

        shell.showBusy(false)

        if (intent.data == null) {
            controller.selectStartTab()
        } else {
            handleIntent(intent)
        }
        tabsLoaded = true
        pendingIntent?.let {
            pendingIntent = null
            handleIntent(it)
        }

        shell.onTabsLoaded()
        if (intent.getBooleanExtra(KEY_SHOW_DOWNLOADS, false) && policy.downloads) {
            shell.showAllDownloads(intent.getLongExtra(KEY_DOWNLOAD_ID, 0L).takeIf { it > 0L })
        }
        //the window of the player was expanded after this activity had been destroyed
        if (intent.getBooleanExtra(KEY_SHOW_PIP_PLAYER, false) && policy.pictureInPicture) showPictureInPicturePlayer()
        if (policy.appUpdates && autoUpdateModel.needAutoCheckUpdates &&
            autoUpdateModel.updateChecker.versionCheckResult == null &&
                !autoUpdateModel.lastUpdateNotificationTime.sameDay(Calendar.getInstance())) {
            autoUpdateModel.checkUpdate(false){
                if (autoUpdateModel.updateChecker.hasUpdate()) {
                    autoUpdateModel.showUpdateDialogIfNeeded(this@MainActivity)
                }
            }
        }
    }

    private fun handleIntent(intent: Intent) {
        Log.d(TAG, "handleIntent: " + intent.data?.buildUpon()?.fragment(null)?.build())
        val url = intent.data.toString()
        //a tile of a home screen channel: the tab that is there already (the tabs of the channel are those
        //of the main profile, their ids mean nothing in another one)
        val tabId = if (policy.homeChannels) intent.getLongExtra(EXTRA_TAB_ID, 0L) else 0L
        val reuseUrl = if (intent.getBooleanExtra(EXTRA_REUSE_TAB, false)) url else null
        controller.tabsStates.firstOrNull {
            (tabId != 0L && it.id == tabId) || (reuseUrl != null && it.url.trimEnd('/') == reuseUrl.trimEnd('/'))
        }?.let {
            controller.changeTab(it)
            shell.showPage()
            return
        }
        if (intent.getBooleanExtra("com.phlox.tvwebbrowser.EXTRA_OPEN_IN_SAME_TAB", false) &&
            controller.tabsStates.isNotEmpty()) {
            if (controller.currentTab.value == null) {
                controller.changeTab(controller.tabsStates[0])
            }
            navigate(intent.data.toString())
            return
        }

        openInNewTab(intent.data.toString(), controller.tabsStates.size)
    }

    override fun openInNewTab(url: String, index: Int): BrowserTab? {
        val tab = controller.openInNewTab(url, index, navigateImmediately = true) ?: return null
        shell.showPage()
        return tab
    }

    private val tabViewHost = object : BrowserController.TabViewHost {
        override val tabsContainer: ViewGroup
            get() = shell.tabsContainer

        override fun createTabView(tab: BrowserTab): View? = createWebView(tab)

        override fun uiHost(tab: BrowserTab): EngineUiHost = TabUiHost(this@MainActivity, tab, controller,
            downloadLauncher, tabUiScreen)
    }

    private val tabUiScreen = object : TabUiHost.Screen {
        override val tabsContainer: ViewGroup
            get() = shell.tabsContainer

        override fun startVoiceSearch() = commands.startVoiceSearch()

        override fun openInNewTabAfterCurrent(url: String) = this@MainActivity.openInNewTabAfterCurrent(url)

        override fun showContextMenu(
            tab: BrowserTab, uiHost: EngineUiHost, cursorDrawer: CursorDrawerDelegate,
            baseUri: String?, linkUri: String?, srcUri: String?,
            title: String?, altText: String?, textContent: String?, x: Int, y: Int
        ) {
            shell.showContextMenu(tab, uiHost, cursorDrawer, baseUri, linkUri, srcUri, title, altText, textContent, x, y)
        }

        override fun onFullscreenChanged(fullscreen: Boolean) {
            isFullscreen = fullscreen
            shell.onFullscreenChanged(fullscreen)
            if (!fullscreen) {
                //the engine view may leave fullscreen by itself on BACK down (WebView does)
                backNavigationEventsAdapter.consumeCurrentPress()
            }
        }

        override fun onPageScrolled(scrollY: Int, dy: Int) = shell.onPageScrolled(scrollY, dy)
    }

    private fun openInNewTabAfterCurrent(url: String) {
        controller.openInNewTabAfterCurrent(url, navigateImmediately = true) ?: return
        shell.showPage()
    }

    private fun onBrowserUiEvent(event: BrowserUiEvent) {
        when (event) {
            BrowserUiEvent.LocalNetworkPermissionNeeded -> requestLocalNetworkPermission()
            is BrowserUiEvent.OpenInExternalApp ->
                if (!refusedByProfile(policy.externalApps)) TabUiHost.openInExternalApp(this, event.url)
            is BrowserUiEvent.AskToOpenInExternalApp ->
                if (!refusedByProfile(policy.externalApps)) askUserAndOpenInExternalApp(event.url, event.intent)
            BrowserUiEvent.NoAppToOpenUrl ->
                Utils.showToast(applicationContext, getString(R.string.err_no_app_to_handle_url))
            else -> shell.onUiEvent(event)
        }
    }

    private fun requestLocalNetworkPermission() {
        launchForResult(ActivityResultContracts.RequestPermission(), Manifest.permission.ACCESS_LOCAL_NETWORK) { granted ->
            if (granted) {
                val currentTab = controller.currentTab.value
                if (currentTab != null) {
                    navigate(currentTab.url)
                }
            } else {
                Utils.showToast(this, R.string.local_network_access_denied)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: BrowserTab): View? {
        val webView: View
        try {
            webView = tab.webEngine.getOrCreateView(this)
        } catch (e: Throwable) {
            e.printStackTrace()

            WebEngineFactory.onViewCreationFailed(this, e)
            return null
        }

        //the kids profile is not filled with what the system saved for its owner (addresses, cards)
        if (!policy.autofill && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        shell.onTabViewCreated(tab)

        return webView
    }

    override fun onTrimMemory(level: Int) {
        controller.trimMemory()
        super.onTrimMemory(level)
    }

    override fun onStart() {
        super.onStart()
        //the main profile was away from the screen for too long: it is locked
        if (space != BrowserSpace.KIDS) profileLock.onMainBackOnScreen()
        if (routeToWantedSpace()) return
        downloadLauncher.onActivityStarted()
    }

    override fun onStop() {
        super.onStop()
        downloadLauncher.onActivityStopped()
        //the browser left the screen (not for a picker it waits for, not to come back in another process)
        if (space != BrowserSpace.KIDS && !switchingProcess && !isChangingConfigurations &&
            !ActivityResults.awaited && !TVBro.restartsMainActivity) {
            profileLock.onMainLeftScreen()
        }
        if (!policy.homeChannels) return
        //the home screen shows now: its channels get what changed (the tabs once their files are written)
        val container = TVBro.container
        container.appScope.launch {
            container.tabWrites.awaitPending()
            HomeChannels.sync(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        updateNetworkAvailability()
        cm.registerDefaultNetworkCallback(networkCallback)
        controller.onActivityResume()
    }

    override fun onPause() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(networkCallback)
        controller.onActivityPause()
        super.onPause()
    }

    override fun navigate(url: String) {
        if (controller.currentTab.value == null) {
            //opening a new tab also shows it
            openInNewTab(url, 0)
        } else {
            controller.navigate(url)
        }
    }

    override fun search(text: String) {
        val url = UrlInputResolver.resolve(text, config.searchEngineURL.value)
        if (url == null) {
            Utils.showToast(this, R.string.error)
            return
        }
        navigate(url)
    }

    override fun toggleIncognitoMode() {
        if (refusedByProfile(policy.incognito)) return
        toggleIncognitoMode(true)
    }

    private fun toggleIncognitoMode(andSwitchProcess: Boolean) = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "toggleIncognitoMode andSwitchProcess: $andSwitchProcess")
        val becomingIncognitoMode = !config.incognitoMode
        shell.showBusy(true)
        if (!becomingIncognitoMode) {
            WebEngineFactory.clearIncognitoBrowsingData(this@MainActivity)
            controller.onCloseAllTabs().join()
            controller.currentTab.value = null
            WebEngineFactory.deleteIncognitoData()
        }
        shell.showBusy(false)
        config.incognitoMode = becomingIncognitoMode
        if (andSwitchProcess) {
            if (becomingIncognitoMode) {
                //the process is killed without pausing the activity
                controller.onActivityPause()
            }
            switchProcess(if (becomingIncognitoMode) BrowserSpace.INCOGNITO else BrowserSpace.MAIN)
        }
    }

    /** [intentToForward]: the intent of this activity, when it came to the wrong process; null on a toggle */
    private fun switchProcess(space: BrowserSpace, intentToForward: Intent? = null, clearTask: Boolean = true) {
        Log.d(TAG, "switchProcess space: $space")
        switchingProcess = true
        val app = applicationContext
        val intent = switchProcessIntent(app, space, intentToForward, Process.myPid(), clearTask)
        //a process that has loaded no engine has nothing to keep
        val engineUsed = engineUsed
        TVBro.container.appScope.launch {
            //tabs state saved on activity pause should not be lost
            TVBro.container.tabWrites.awaitPending()
            //and the cookies set a moment ago: the process is killed
            if (engineUsed) WebEngineFactory.flushData()
            app.startActivity(intent)
            exitProcess(0)
        }
    }

    val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            backNavigationEventsAdapter.dispatchSystemBackNavigationEvent()
        }
    }

    private val backNavigationEventsAdapter = BackNavigationEventsAdapter(
        onEmulatedBackEvent = {
            Log.d(TAG, "onBackNavigation")
            if (!hideSoftwareKeyboardIfVisible()) {
                screenBackDispatcher.onBackPressed()
            }
        }
    )

    override val backEvents: BackNavigationEventsAdapter get() = backNavigationEventsAdapter

    /**
     * Back of this screen: each overlay has its callback here and enables it while it is shown.
     * Only [backNavigationEventsAdapter] (key, system and game controller back) feeds it;
     * the activity's own dispatcher has one always enabled callback that goes to the adapter.
     * The shell adds its callbacks ([BrowserShell.registerBackCallbacks]).
     */
    private val screenBackDispatcher = OnBackPressedDispatcher()

    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            controller.currentTab.value?.webEngine?.hideFullscreenView()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val localCallback = window.callback
        //the D-pad of a game controller (a hat) controls the video as the one of a remote does
        val joystickRouter = JoystickDpadRouter({ shell.takesDpad() }, localCallback::dispatchGenericMotionEvent)
        window.callback = object : Window.Callback by localCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                //Log.d(TAG, "dispatchKeyEvent event: $event")
                backNavigationEventsAdapter.dispatchKeyEvent(event)

                val keyCode = if (event.keyCode != 0) event.keyCode else event.scanCode
                val keyCodeBackNavigation = keyCode == KeyEvent.KEYCODE_ESCAPE ||
                        keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK
                if (keyCodeBackNavigation && isFullscreen) {
                    //the back callbacks decide (the video mode may take Back), not the fullscreen view of the engine;
                    //where the window gives Back to its callbacks, fullscreenOverlayBack does the same
                    return true
                }
                if (!keyCodeBackNavigation && shell.dispatchKeyEvent(event)) {
                    return true
                }
                if (!keyCodeBackNavigation && ShortcutMgr.getInstance(this@MainActivity).handle(event, commands,
                        typing = currentFocus?.onCheckIsTextEditor() == true,
                        passOn = { localCallback.dispatchKeyEvent(it) })) {
                    return true
                }

                return localCallback.dispatchKeyEvent(event)
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                //Log.d(TAG, "dispatchGenericMotionEvent event: $event")
                if (backNavigationEventsAdapter.dispatchGenericMotionEvent(event)) {
                    return true
                }
                return joystickRouter.dispatch(event)
            }
        }
    }

    /**
     * When the IME is visible, the first back press should only dismiss it; this mirrors standard
     * Android behavior and avoids navigating away while the user is typing.
     *
     * @return true if the IME was visible and a hide was requested.
     */
    private fun hideSoftwareKeyboardIfVisible(): Boolean {
        val root = window.decorView.rootView
        val insets = ViewCompat.getRootWindowInsets(root) ?: return false
        if (!insets.isVisible(WindowInsetsCompat.Type.ime())) {
            return false
        }
        val view = currentFocus ?: root
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        return true
    }

    private fun initiateVoiceSearch() {
        shell.showPage()
        voiceSearchHelper.initiateVoiceSearch(object : VoiceSearchHelper.Callback {
            override fun onResult(text: String?) {
                if (text == null) {
                    voiceSearchHelper.showCanNotRecognize()
                    return
                }
                search(text)
                shell.showPage()
            }
        })
    }

    private fun askUserAndOpenInExternalApp(url: String, intent: Intent) {
        if (openUrlInExternalAppDialog != null) {
            return
        }
        openUrlInExternalAppDialog = AlertDialog.Builder(this)
            .setTitle(R.string.site_asks_to_open_unknown_url)
            .setMessage(getString(R.string.site_asks_to_open_unknown_url_message) + "\n\n" + url)
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.no, null)
            .setOnDismissListener {
                openUrlInExternalAppDialog = null
            }
            .show()
    }
}
