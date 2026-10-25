package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.browser.BrowserCommands
import com.phlox.tvwebbrowser.browser.BrowserController
import com.phlox.tvwebbrowser.browser.BrowserUiEvent
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.browser.DownloadsModel
import com.phlox.tvwebbrowser.browser.UrlInputResolver
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.sync.SyncLink
import com.phlox.tvwebbrowser.utils.BackNavigationEventsAdapter
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.VoiceSearchHelper
import com.phlox.tvwebbrowser.utils.launchForResult
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.sameDay
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        /** Open the downloads (the notification of the download service) */
        const val KEY_SHOW_DOWNLOADS = "show_downloads"

        /**
         * The intent that starts the browser activity of the other process (incognito or not) and makes it
         * kill this one. [source] (an intent that came to the activity of the wrong process: a link to open,
         * a sync link) goes there whole, so it is handled as if it had come there.
         */
        internal fun switchProcessIntent(context: Context, incognitoMode: Boolean, source: Intent?, pidToKill: Int): Intent {
            val activityClass = if (incognitoMode) IncognitoModeMainActivity::class.java
            else MainActivity::class.java
            val intent = Intent(context, activityClass)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (source != null) {
                intent.action = source.action
                //setData and setType clear each other
                intent.setDataAndType(source.data, source.type)
                source.extras?.let { intent.putExtras(it) }
            }
            //after the extras: an intent restored from the history may hold the pid of a process gone
            intent.putExtra(KEY_PROCESS_ID_TO_KILL, pidToKill)
            return intent
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
        }
    protected val config = AppContext.provideConfig()
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

        val incognitoMode = config.incognitoMode
        Log.d(TAG, "onCreate incognitoMode: $incognitoMode")
        if (incognitoMode xor (this is IncognitoModeMainActivity)) {
            switchProcess(incognitoMode, intent)
            finish()
            return
        }
        val pidToKill = intent?.getIntExtra(KEY_PROCESS_ID_TO_KILL, -1) ?: -1
        if (pidToKill != -1) {
            Process.killProcess(pidToKill)
        }

        if (incognitoMode) {
            WebEngineFactory.prepareIncognitoProcess()
        }
        AppSettingsStore.rememberStartupValues(config)
        settingsModel = ActiveModelsRepository.get(SettingsModel::class, this)
        controller = ActiveModelsRepository.get(BrowserController::class, this)
        autoUpdateModel = ActiveModelsRepository.get(AutoUpdateModel::class, this)
        uiHandler = Handler(Looper.getMainLooper())
        uiMode = UiModeSelector.select(this, config.interfaceMode)
        shell = when (uiMode) {
            UiMode.TV -> TvShell(this)
            UiMode.PHONE -> PhoneShell(this)
        }
        requestedOrientation = shell.screenOrientation
        setContentView(shell.root)
        controller.viewHost = tabViewHost
        controller.interfaceDesktopView = uiMode == UiMode.TV

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
            if (config.incognitoMode) {
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
    fun showSettings(category: SettingsNavigator.Category? = null) = shell.showSettings(category)

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
            .setSingleChoiceItems(R.array.site_views, siteViewPosition(state.desktopView)) { dialog, position ->
                controller.setSiteView(tab, desktop = position == SITE_VIEW_DESKTOP)
                dialog.dismiss()
            }
            .show()
    }

    override fun clearSiteData() {
        val tab = controller.currentTab.value ?: return
        val host = controller.currentTabState.value.host
        if (host.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_site_data_title, host))
            .setMessage(R.string.clear_site_data_message)
            .setPositiveButton(R.string.clear) { _, _ ->
                lifecycleScope.launch {
                    try {
                        controller.clearSiteData(tab)
                    } catch (e: Exception) {
                        Log.e(TAG, "clearSiteData", e)
                        Utils.showToast(this@MainActivity, R.string.clear_site_data_failed)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun download(request: DownloadRequest) = downloadLauncher.download(request)

    override suspend fun receiveDownload(request: DownloadRequest) = downloadLauncher.receive(request)

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
            if (!shell.onMediaCommand(MediaCommand.TOGGLE)) controller.togglePlayback()
        }

        override fun stopPlayback() {
            if (!shell.onMediaCommand(MediaCommand.STOP)) controller.stopPlayback()
        }

        override fun rewind() {
            if (!shell.onMediaCommand(MediaCommand.REWIND)) controller.rewind()
        }

        override fun fastForward() {
            if (!shell.onMediaCommand(MediaCommand.FAST_FORWARD)) controller.fastForward()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        //here properties can be uninitialized in case of wrong activity for incognito mode
        //detection and force activity restart in onCreate()
        if (::controller.isInitialized) {
            controller.onDetachActivity(tabViewHost)
        }
        super.onDestroy()
    }

    @SuppressLint("MissingSuperCall")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(KEY_SHOW_DOWNLOADS, false)) {
            shell.showAllDownloads()
        }
        if (intent.data != null) {
            if (tabsLoaded) {
                handleIntent(intent)
            } else {
                //the web engine is not initialized and the saved tabs are not loaded yet
                pendingIntent = intent
            }
        }
    }

    private fun loadState() = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "loadState")
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
        if (intent.getBooleanExtra(KEY_SHOW_DOWNLOADS, false)) {
            shell.showAllDownloads()
        }
        if (autoUpdateModel.needAutoCheckUpdates &&
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
        if (SyncLink.isSyncUrl(url) && shell.handleSyncLink(SyncLink.parse(url))) {
            if (controller.currentTab.value == null) controller.selectStartTab()
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
            is BrowserUiEvent.OpenInExternalApp -> TabUiHost.openInExternalApp(this, event.url)
            is BrowserUiEvent.AskToOpenInExternalApp -> askUserAndOpenInExternalApp(event.url, event.intent)
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

        shell.onTabViewCreated(tab)

        return webView
    }

    override fun onTrimMemory(level: Int) {
        controller.trimMemory()
        super.onTrimMemory(level)
    }

    override fun onStart() {
        super.onStart()
        downloadLauncher.onActivityStarted()
    }

    override fun onStop() {
        super.onStop()
        downloadLauncher.onActivityStopped()
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
            switchProcess(becomingIncognitoMode)
        }
    }

    /** [intentToForward]: the intent of this activity, when it came to the wrong process; null on a toggle */
    private fun switchProcess(incognitoMode: Boolean, intentToForward: Intent? = null) {
        Log.d(TAG, "switchProcess incognitoMode: $incognitoMode")
        val app = applicationContext
        val intent = switchProcessIntent(app, incognitoMode, intentToForward, Process.myPid())
        TVBro.container.appScope.launch {
            //tabs state saved on activity pause should not be lost
            TVBro.container.tabWrites.awaitPending()
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
        window.callback = object : Window.Callback by localCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                //Log.d(TAG, "dispatchKeyEvent event: $event")
                backNavigationEventsAdapter.dispatchKeyEvent(event)

                val keyCode = if (event.keyCode != 0) event.keyCode else event.scanCode
                val keyCodeBackNavigation = keyCode == KeyEvent.KEYCODE_ESCAPE ||
                        keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK
                if (keyCodeBackNavigation && isFullscreen) {
                    //the back callbacks decide (the video mode may take Back), not the fullscreen view of the engine
                    return true
                }
                if (!keyCodeBackNavigation && shell.dispatchKeyEvent(event)) {
                    return true
                }
                if (!keyCodeBackNavigation &&
                    ShortcutMgr.getInstance(this@MainActivity).handle(event, commands)) {
                    return true
                }

                return localCallback.dispatchKeyEvent(event)
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                //Log.d(TAG, "dispatchGenericMotionEvent event: $event")
                if (backNavigationEventsAdapter.dispatchGenericMotionEvent(event)) {
                    return true
                }
                return localCallback.dispatchGenericMotionEvent(event)
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
