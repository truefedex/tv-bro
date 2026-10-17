package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Environment
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
import com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity
import com.phlox.tvwebbrowser.activity.history.HistoryActivity
import com.phlox.tvwebbrowser.activity.main.dialogs.favorites.FavoriteEditorDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.favorites.FavoritesDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.settings.SettingsDialog
import com.phlox.tvwebbrowser.browser.BrowserCommands
import com.phlox.tvwebbrowser.browser.BrowserController
import com.phlox.tvwebbrowser.browser.BrowserUiEvent
import com.phlox.tvwebbrowser.browser.UrlInputResolver
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.BackNavigationEventsAdapter
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.VoiceSearchHelper
import com.phlox.tvwebbrowser.utils.launchForResult
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.sameDay
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
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
    }

    private lateinit var uiMode: UiMode
    private lateinit var shell: BrowserShell
    override lateinit var controller: BrowserController
    override lateinit var settingsModel: SettingsModel
    private lateinit var adblockModel: AdblockModel
    private lateinit var autoUpdateModel: AutoUpdateModel
    private lateinit var uiHandler: Handler
    private var isFullscreen: Boolean = false
        set(value) {
            field = value
            fullscreenBackCallback.isEnabled = value
        }
    protected val config = AppContext.provideConfig()
    private val voiceSearchHelper = VoiceSearchHelper(this)
    private val downloadLauncher = DownloadLauncher(this) { onDownloadStarted(it.filename) }
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
            switchProcess(incognitoMode, intent?.extras)
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
        settingsModel = ActiveModelsRepository.get(SettingsModel::class, this)
        adblockModel = ActiveModelsRepository.get(AdblockModel::class, this)
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
        controller.adBlocker = adblockModel

        config.userAgentString.subscribe(this.lifecycle, false) {
            for (tab in controller.tabsStates) {
                tab.webEngine.userAgentString = it
            }
        }

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
            controller.uiEvents.collect { onBrowserUiEvent(it) }
        }

        onBackPressedDispatcher.addCallback(onBackPressedCallback)
        shell.registerBackCallbacks(screenBackDispatcher, fullscreenBackCallback)

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

    override fun showDownloads() {
        startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
    }

    override fun showHistory() {
        launchForResult(ActivityResultContracts.StartActivityForResult(),
            Intent(this@MainActivity, HistoryActivity::class.java)) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val url = result.data?.getStringExtra(HistoryActivity.KEY_URL)
                if (url != null) {
                    navigate(url)
                }
            }
        }
    }

    override fun showFavorites() {
        val currentTab = controller.currentTab.value
        val currentPageTitle = currentTab?.title ?: ""
        val currentPageUrl = currentTab?.url ?: ""

        FavoritesDialog(this@MainActivity, lifecycleScope, object : FavoritesDialog.Callback {
            override fun onFavoriteChoosen(item: FavoriteItem?) {
                navigate(item!!.url!!)
            }
        }, currentPageTitle, currentPageUrl).show()
    }

    override fun showSettings() {
        SettingsDialog(this, settingsModel, touchUi = uiMode == UiMode.PHONE).show()
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

    override fun addBookmark() {
        val tab = controller.currentTab.value ?: return
        val item = FavoriteItem()
        item.title = tab.title
        item.url = tab.url
        FavoriteEditorDialog(this, object : FavoriteEditorDialog.Callback {
            override fun onDone(item: FavoriteItem) {
                lifecycleScope.launch {
                    TVBro.container.data.favorites.save(item)
                    Utils.showToast(this@MainActivity, R.string.bookmark_added)
                }
            }
        }, item).show()
    }

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

        override fun togglePlayback() = controller.togglePlayback()

        override fun stopPlayback() = controller.stopPlayback()

        override fun rewind() = controller.rewind()

        override fun fastForward() = controller.fastForward()
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
        Log.d(TAG, "handleIntent: " + intent.data)
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

        var ua = config.userAgentString.value
        if (ua?.contains(Config.TV_BRO_UA_PREFIX) == true) {//legacy ua string - now default one should be used
            config.userAgentString.value = null
            ua = null
        }
        if (ua != null) {
            tab.webEngine.userAgentString = ua
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
        downloadLauncher.bindService()
    }

    override fun onStop() {
        super.onStop()
        downloadLauncher.unbindService()
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

    private fun switchProcess(incognitoMode: Boolean, intentDataToCopy: Bundle? = null) {
        Log.d(TAG, "switchProcess incognitoMode: $incognitoMode")
        val activityClass = if (incognitoMode) IncognitoModeMainActivity::class.java
        else MainActivity::class.java
        val app = applicationContext
        val intent = Intent(app, activityClass)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra(KEY_PROCESS_ID_TO_KILL, Process.myPid())
        intentDataToCopy?.let {
            intent.putExtras(it)
        }
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

    private fun onDownloadStarted(fileName: String) {
        Utils.showToast(this, getString(R.string.download_started,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + File.separator + fileName))
        shell.onDownloadStarted()
    }

    private fun initiateVoiceSearch() {
        shell.showPage()
        voiceSearchHelper.initiateVoiceSearch(object : VoiceSearchHelper.Callback {
            override fun onResult(text: String?) {
                if (text == null) {
                    Utils.showToast(this@MainActivity, getString(R.string.can_not_recognize))
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
