package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
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
import com.phlox.tvwebbrowser.activity.main.view.ActionBar
import com.phlox.tvwebbrowser.activity.main.view.CursorMenuView
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.Listener
import com.phlox.tvwebbrowser.browser.BrowserController
import com.phlox.tvwebbrowser.browser.BrowserUiEvent
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.browser.UrlInputResolver
import com.phlox.tvwebbrowser.databinding.ActivityMainBinding
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.BackNavigationEventsAdapter
import com.phlox.tvwebbrowser.utils.BaseAnimationListener
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.VoiceSearchHelper
import com.phlox.tvwebbrowser.utils.launchForResult
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.childs
import com.phlox.tvwebbrowser.utils.sameDay
import com.phlox.tvwebbrowser.webengine.FeatureDelivery
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.webengine.virtualCursor
import com.phlox.tvwebbrowser.widgets.NotificationView
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Calendar
import kotlin.system.exitProcess


open class MainActivity : AppCompatActivity(), ActionBar.Callback {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val KEY_PROCESS_ID_TO_KILL = "proc_id_to_kill"
    }

    private lateinit var vb: ActivityMainBinding
    private lateinit var controller: BrowserController
    private lateinit var settingsModel: SettingsModel
    private lateinit var adblockModel: AdblockModel
    private lateinit var autoUpdateModel: AutoUpdateModel
    private lateinit var uiHandler: Handler
    private var isFullscreen: Boolean = false
    protected val config = AppContext.provideConfig()
    private val voiceSearchHelper = VoiceSearchHelper(this)
    private val downloadLauncher = DownloadLauncher(this) { onDownloadStarted(it.filename) }
    var openUrlInExternalAppDialog: AlertDialog? = null
    private var linkActionsMenu: PopupMenu? = null
    private var tabsLoaded = false
    /** Url to open that arrived before the tabs were loaded */
    private var pendingIntent: Intent? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        FeatureDelivery.installActivity(this)
    }

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
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)
        controller.viewHost = tabViewHost
        controller.adBlocker = adblockModel

        vb.ivMiniatures.visibility = View.INVISIBLE
        vb.llBottomPanel.visibility = View.INVISIBLE
        vb.rlActionBar.visibility = View.INVISIBLE
        vb.progressBar.visibility = View.GONE

        vb.vTabs.listener = tabsListener

        vb.ibAdBlock.setOnClickListener { toggleAdBlockForTab() }
        vb.ibPopupBlock.setOnClickListener { showPopupBlockOptions() }
        vb.ibHome.setOnClickListener { navigate(settingsModel.homePage) }
        vb.ibBack.setOnClickListener { navigateBack() }
        vb.ibForward.setOnClickListener { controller.goForward() }
        vb.ibRefresh.setOnClickListener { refresh() }
        vb.ibCloseTab.setOnClickListener { controller.currentTab.value?.apply { closeTab(this) } }

        vb.vActionBar.callback = this

        vb.llBottomPanel.childs.forEach {
            it.setOnTouchListener(bottomButtonsOnTouchListener)
            it.onFocusChangeListener = bottomButtonsFocusListener
            it.setOnKeyListener(bottomButtonsKeyListener)
        }

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
            controller.currentTabState.collect { renderTabState(it) }
        }
        lifecycleScope.launch {
            controller.uiEvents.collect { onBrowserUiEvent(it) }
        }

        onBackPressedDispatcher.addCallback(onBackPressedCallback)

        loadState()
    }

    private var progressBarHideRunnable: Runnable = Runnable {
        val anim = AnimationUtils.loadAnimation(this@MainActivity, android.R.anim.fade_out)
        anim.setAnimationListener(object : BaseAnimationListener() {
            override fun onAnimationEnd(animation: Animation) {
                vb.progressBar.visibility = View.GONE
            }
        })
        vb.progressBar.startAnimation(anim)
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

    private val displayThumbnailRunnable = object : Runnable {
        var tabState: WebTabState? = null
        override fun run() {
            tabState?.let {
                lifecycleScope.launch(Dispatchers.Main) {
                    displayThumbnail(it)
                }
            }
        }
    }

    private val tabsListener = object : Listener {
        override fun onTitleChanged(index: Int) {
            Log.d(TAG, "onTitleChanged: $index")
            val tab = tabByTitleIndex(index)
            vb.vActionBar.setAddressBoxText(tab?.url ?: "")
            uiHandler.removeCallbacks(displayThumbnailRunnable)
            displayThumbnailRunnable.tabState = tab
            uiHandler.postDelayed(displayThumbnailRunnable, 200)
        }

        override fun onTitleSelected(index: Int) {
            syncTabWithTitles()
            hideMenuOverlay()
        }

        override fun onAddNewTabSelected() {
            openInNewTab(settingsModel.homePage, controller.tabsStates.size)
        }

        override fun closeTab(tabState: WebTabState?) = this@MainActivity.closeTab(tabState)

        override fun openInNewTab(url: String, tabIndex: Int) {
            this@MainActivity.openInNewTab(url, tabIndex,
                needToHideMenuOverlay = false,
                navigateImmediately = true
            )
        }
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
                hideMenuOverlay()
            }
        }
        hideMenuOverlay()
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
        hideMenuOverlay()
    }

    private val bottomButtonsOnTouchListener = View.OnTouchListener{ v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                return@OnTouchListener true
            }
            MotionEvent.ACTION_UP -> {
                hideMenuOverlay(false)
                v.performClick()
                return@OnTouchListener true
            }
            else -> return@OnTouchListener false
        }
    }

    private val bottomButtonsFocusListener = View.OnFocusChangeListener { view, hasFocus ->
        if (hasFocus) {
            hideMenuOverlay(false)
        }
    }

    private val bottomButtonsKeyListener = View.OnKeyListener { view, i, keyEvent ->
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    hideBottomPanel()
                    controller.currentTab.value?.webEngine?.getView()?.requestFocus()
                }
                return@OnKeyListener true
            }
        }
        false
    }

    private fun tabByTitleIndex(index: Int) =
            if (index >= 0 && index < controller.tabsStates.size) controller.tabsStates[index] else null

    override fun showSettings() {
        SettingsDialog(this, settingsModel).show()
    }

    override fun onExtendedAddressBarMode() {
        vb.llBottomPanel.visibility = View.INVISIBLE
    }

    override fun onUrlInputDone() {
        hideMenuOverlay()
    }

    fun navigateBack(goHomeIfNoHistory: Boolean = false) {
        if (controller.goBack()) {
            return
        } else if (goHomeIfNoHistory) {
            navigate(settingsModel.homePage)
        } else if (vb.rlActionBar.visibility != View.VISIBLE) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    fun refresh() {
        controller.reload()
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
        WebEngineFactory.initialize(this@MainActivity, vb.flWebViewContainer)

        vb.progressBarGeneric.visibility = View.VISIBLE
        vb.progressBarGeneric.requestFocus()
        controller.loadState().join()

        if (!isActive) {
            return@launch
        }

        vb.progressBarGeneric.visibility = View.GONE

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

        val currentTab = controller.currentTab.value
        if (currentTab == null || currentTab.url == settingsModel.homePage) {
            showMenuOverlay()
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
        Log.d(TAG, "handleIntent: " + intent.data)
        if (intent.getBooleanExtra("com.phlox.tvwebbrowser.EXTRA_OPEN_IN_SAME_TAB", false) &&
            controller.tabsStates.isNotEmpty()) {
            if (controller.currentTab.value == null) {
                changeTab(controller.tabsStates[0])
            }
            navigate(intent.data.toString())
            return
        }

        openInNewTab(
            intent.data.toString(), controller.tabsStates.size, needToHideMenuOverlay = true,
            navigateImmediately = true
        )
    }

    private fun openInNewTab(url: String?, index: Int = 0, needToHideMenuOverlay: Boolean = true, navigateImmediately: Boolean): WebEngine? {
        if (url == null) {
            return null
        }
        val tab = controller.openInNewTab(url, index, navigateImmediately) ?: return null
        if (needToHideMenuOverlay && vb.rlActionBar.visibility == View.VISIBLE) {
            hideMenuOverlay(true)
        }
        return tab.webEngine
    }

    private fun closeTab(tab: WebTabState?) {
        if (tab == null) return
        controller.closeTab(tab)
        hideMenuOverlay(true)
        hideBottomPanel()
    }

    private fun changeTab(newTab: WebTabState) {
        controller.changeTab(newTab)
    }

    private val tabViewHost = object : BrowserController.TabViewHost {
        override val tabsContainer: ViewGroup
            get() = vb.flWebViewContainer

        override fun createTabView(tab: WebTabState): View? = createWebView(tab)

        override fun uiHost(tab: WebTabState): EngineUiHost = TabUiHost(tab)
    }

    private fun openInNewTabAfterCurrent(url: String) {
        controller.openInNewTabAfterCurrent(url, navigateImmediately = true) ?: return
        if (vb.rlActionBar.visibility == View.VISIBLE) {
            hideMenuOverlay(true)
        }
    }

    private var renderedTabIdentity: Int? = null

    private fun renderTabState(state: CurrentTabState) {
        vb.ibBack.isEnabled = state.canGoBack
        vb.ibForward.isEnabled = state.canGoForward

        vb.ibAdBlock.setImageResource(if (state.adBlockEnabled) R.drawable.ic_adblock_on else R.drawable.ic_adblock_off)
        vb.tvBlockedAdCounter.visibility = if (state.adBlockEnabled && state.blockedAds != 0) View.VISIBLE else View.GONE
        vb.tvBlockedAdCounter.text = state.blockedAds.toString()

        vb.tvBlockedPopupCounter.visibility = if (state.blockedPopups != 0) View.VISIBLE else View.GONE
        vb.tvBlockedPopupCounter.text = state.blockedPopups.toString()

        vb.vActionBar.setAddressBoxTextColor(if (state.certificateError) Color.RED else
            ContextCompat.getColor(this, R.color.default_url_color))

        val tabChanged = state.tabIdentity != renderedTabIdentity
        renderedTabIdentity = state.tabIdentity
        //the address is not touched while the user edits it or browses the tabs list
        //(then the address of the focused tab is shown)
        if (tabChanged || (!vb.vActionBar.isAddressBoxFocused() &&
                    tabByTitleIndex(vb.vTabs.current) == controller.currentTab.value)) {
            vb.vActionBar.setAddressBoxText(state.url)
        }
    }

    private fun onBrowserUiEvent(event: BrowserUiEvent) {
        when (event) {
            is BrowserUiEvent.Progress -> showProgress(event.value)
            is BrowserUiEvent.TabUpdated -> vb.vTabs.onTabUpdated(event.tab)
            is BrowserUiEvent.ThumbnailUpdated -> {
                if (vb.rlActionBar.isVisible && event.tab == controller.currentTab.value) {
                    lifecycleScope.launch { displayThumbnail(event.tab) }
                }
            }
            is BrowserUiEvent.PopupBlocked -> {
                val msg = getString(if (event.newTab) R.string.new_tab_blocked else R.string.popup_dialog_blocked)
                NotificationView.showBottomRight(vb.rlRoot, R.drawable.ic_block_popups, msg)
            }
            BrowserUiEvent.TabOpenedByPage -> {
                if (vb.rlActionBar.visibility == View.VISIBLE) {
                    hideMenuOverlay(true)
                }
            }
            BrowserUiEvent.TabClosedByPage -> {
                hideMenuOverlay(true)
                hideBottomPanel()
            }
            BrowserUiEvent.LocalNetworkPermissionNeeded -> requestLocalNetworkPermission()
            is BrowserUiEvent.OpenInExternalApp -> openInExternalApp(event.url)
            is BrowserUiEvent.AskToOpenInExternalApp -> askUserAndOpenInExternalApp(event.url, event.intent)
            BrowserUiEvent.NoAppToOpenUrl ->
                Utils.showToast(applicationContext, getString(R.string.err_no_app_to_handle_url))
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

    private fun openInExternalApp(url: String) {
        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val activityComponent = intent.resolveActivity(packageManager)
        if (activityComponent != null && activityComponent.packageName == packageName) {
            Toast.makeText(this, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProgress(newProgress: Int) {
        vb.progressBar.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            vb.progressBar.setProgress(newProgress, true)
        } else {
            vb.progressBar.progress = newProgress
        }
        uiHandler.removeCallbacks(progressBarHideRunnable)
        if (newProgress == 100) {
            uiHandler.postDelayed(progressBarHideRunnable, 1000)
        } else {
            uiHandler.postDelayed(progressBarHideRunnable, 5000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: WebTabState): View? {
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

    private fun toggleAdBlockForTab() {
        controller.toggleAdBlockForCurrentTab()?.let {
            refresh()
        }
    }

    private fun showPopupBlockOptions() {
        val tab = controller.currentTab.value ?: return
        val currentHostConfig = controller.findHostConfig(tab)
        val currentBlockPopupsLevelValue = currentHostConfig?.popupBlockLevel ?: HostConfig.DEFAULT_BLOCK_POPUPS_VALUE
        val hostName = currentHostConfig?.hostName ?: try { URL(tab.url).host } catch (e: Exception) { "" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.block_popups_s, hostName))
            .setSingleChoiceItems(R.array.popup_blocking_level, currentBlockPopupsLevelValue) {
                    dialog, itemId -> lifecycleScope.launch {
                        controller.changePopupBlockingLevel(itemId, tab)
                        dialog.dismiss()
                    }
            }
            .show()
    }

    fun navigate(url: String) {
        if (controller.currentTab.value == null) {
            //opening a new tab also hides the menu
            openInNewTab(url, 0, needToHideMenuOverlay = true, navigateImmediately = true)
        } else {
            controller.navigate(url)
        }
    }

    override fun search(aText: String) {
        val url = UrlInputResolver.resolve(aText, config.searchEngineURL.value)
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
        vb.progressBarGeneric.visibility = View.VISIBLE
        if (!becomingIncognitoMode) {
            WebEngineFactory.clearIncognitoBrowsingData(this@MainActivity)
            controller.onCloseAllTabs().join()
            controller.currentTab.value = null
            WebEngineFactory.deleteIncognitoData()
        }
        vb.progressBarGeneric.visibility = View.GONE
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
        val app = TVBro.instance
        val intent = Intent(app, activityClass)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra(KEY_PROCESS_ID_TO_KILL, Process.myPid())
        intentDataToCopy?.let {
            intent.putExtras(it)
        }
        app.container.appScope.launch {
            //tabs state saved on activity pause should not be lost
            app.container.tabWrites.awaitPending()
            app.startActivity(intent)
            exitProcess(0)
        }
    }

    fun toggleMenu() {
        if (vb.rlActionBar.isInvisible) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            backNavigationEventsAdapter.dispatchSystemBackNavigationEvent()
        }
    }

    private val backNavigationEventsAdapter = BackNavigationEventsAdapter(
        onEmulatedBackEvent = {
            if (!hideSoftwareKeyboardIfVisible()) {
                handleBackNavigation()
            }
        }
    )

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
                val shortcutMgr = ShortcutMgr.getInstance()
                val currentTab = controller.currentTab.value
                if (!keyCodeBackNavigation &&
                    shortcutMgr.handle(event, this@MainActivity, currentTab)) {
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

    private fun handleBackNavigation() {
        Log.d(TAG, "handleBackNavigation")
        val cursor = controller.currentTab.value?.webEngine?.virtualCursor
        if (cursor?.isVirtualCursorMode() == false) {
            cursor.setVirtualCursorMode(true)
            backNavigationEventsAdapter.gameControllersLongPressBForBackNavigation = false
            return
        }

        val cursorDrawer = cursor?.getCursorDrawerDelegate()
        if (vb.vCursorMenu.isVisible) {
            vb.vCursorMenu.close(CursorMenuView.CloseAnimation.ROTATE_OUT)
        } else if (cursorDrawer?.canHandleBackNavigation() == true) {
            cursorDrawer.handleBackNavigation()
        } else if (isFullscreen) {
            controller.currentTab.value?.webEngine?.hideFullscreenView()
        } else if (vb.llBottomPanel.isVisible && !vb.rlActionBar.isVisible) {
            hideBottomPanel()
        } else {
            toggleMenu()
        }
    }

    private fun showMenuOverlay() {
        vb.ivMiniatures.visibility = View.VISIBLE
        vb.llBottomPanel.visibility = View.VISIBLE
        vb.flWebViewContainer.visibility = View.INVISIBLE
        val currentTab = controller.currentTab.value
        if (currentTab != null) {
            lifecycleScope.launch {
                currentTab.thumbnail = currentTab.webEngine.renderThumbnail(currentTab.thumbnail)
                displayThumbnail(currentTab)
            }
        }

        vb.llBottomPanel.translationY = vb.llBottomPanel.height.toFloat()
        vb.llBottomPanel.alpha = 0f
        vb.llBottomPanel.animate()
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .translationY(0f)
                .alpha(1f)
                .withEndAction {
                    vb.vActionBar.catchFocus()
                }
                .start()

        vb.vActionBar.dismissExtendedAddressBarMode()

        vb.rlActionBar.visibility = View.VISIBLE
        vb.rlActionBar.translationY = -vb.rlActionBar.height.toFloat()
        vb.rlActionBar.alpha = 0f
        vb.rlActionBar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()

        vb.ivMiniatures.layoutParams = vb.ivMiniatures.layoutParams.apply { this.height = vb.flWebViewContainer.height }
        vb.ivMiniatures.translationY = 0f
        vb.ivMiniatures.animate()
                .translationY(vb.rlActionBar.height.toFloat())
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()
    }

    private suspend fun displayThumbnail(currentTab: WebTabState?) {
        if (currentTab != null) {
            if (tabByTitleIndex(vb.vTabs.current) != currentTab) return
            vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
            vb.ivMiniatures.visibility = View.VISIBLE
            if (currentTab.thumbnail != null) {
                vb.ivMiniatures.setImageBitmap(currentTab.thumbnail)
            } else if (currentTab.thumbnailHash != null) {
                withContext(Dispatchers.IO) {
                    val thumbnail = currentTab.loadThumbnail()
                    withContext(Dispatchers.Main) {
                        if (thumbnail != null) {
                            vb.ivMiniatures.setImageBitmap(currentTab.thumbnail)
                        } else {
                            vb.ivMiniatures.setImageResource(0)
                        }
                    }
                }
            } else {
                vb.ivMiniatures.setImageResource(0)
            }
        } else {
            vb.llMiniaturePlaceholder.visibility = View.VISIBLE
            vb.ivMiniatures.setImageResource(0)
            vb.ivMiniatures.visibility = View.INVISIBLE
        }
    }

    private fun hideMenuOverlay(hideBottomButtons: Boolean = true) {
        if (vb.rlActionBar.visibility == View.INVISIBLE) {
            return
        }
        if (hideBottomButtons) {
            hideBottomPanel()
        }

        vb.rlActionBar.animate()
                .translationY(-vb.rlActionBar.height.toFloat())
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    vb.rlActionBar.visibility = View.INVISIBLE
                }
                .start()

        if (vb.llMiniaturePlaceholder.visibility == View.VISIBLE) {
            vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
            vb.ivMiniatures.visibility = View.VISIBLE
        }

        vb.ivMiniatures.translationY = vb.rlActionBar.height.toFloat()
        vb.ivMiniatures.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    vb.ivMiniatures.visibility = View.INVISIBLE
                    vb.rlActionBar.visibility = View.INVISIBLE
                    vb.ivMiniatures.setImageResource(0)
                    syncTabWithTitles()
                    vb.flWebViewContainer.visibility = View.VISIBLE
                    if (hideBottomButtons) {
                        controller.currentTab.value?.webEngine?.getView()?.requestFocus()
                    }
                }
                .start()
    }

    private fun syncTabWithTitles() {
        val tab = tabByTitleIndex(vb.vTabs.current)
        if (tab == null) {
            openInNewTab(settingsModel.homePage, if (vb.vTabs.current < 0) 0 else controller.tabsStates.size,
                needToHideMenuOverlay = true,
                navigateImmediately = true
            )
        } else if (!tab.selected) {
            changeTab(tab)
        }
    }

    private fun hideBottomPanel() {
        if (vb.llBottomPanel.visibility != View.VISIBLE) return
        vb.llBottomPanel.animate()
                .setDuration(300)
                .setInterpolator(AccelerateInterpolator())
                .translationY(vb.llBottomPanel.height.toFloat())
                .withEndAction {
                    vb.llBottomPanel.translationY = 0f
                    vb.llBottomPanel.visibility = View.INVISIBLE
                }
                .start()
    }

    private fun onDownloadStarted(fileName: String) {
        Utils.showToast(this, getString(R.string.download_started,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + File.separator + fileName))
        showMenuOverlay()
    }

    override fun initiateVoiceSearch() {
        hideMenuOverlay()
        voiceSearchHelper.initiateVoiceSearch(object : VoiceSearchHelper.Callback {
            override fun onResult(text: String?) {
                if (text == null) {
                    Utils.showToast(this@MainActivity, getString(R.string.can_not_recognize))
                    return
                }
                search(text)
                hideMenuOverlay()
            }
        })
    }

    private fun onEditHomePageBookmark(favoriteItem: FavoriteItem) {
        FavoriteEditorDialog(this, object : FavoriteEditorDialog.Callback {
            override fun onDone(item: FavoriteItem) {
                controller.modelScope.launch { controller.homePageLinks.onBookmarkEdited(item) }
            }
        }, favoriteItem).show()
    }

    /**
     * What the engine of the [tab] needs from this activity.
     */
    private inner class TabUiHost(val tab: WebTabState) : EngineUiHost {
        override fun getActivity(): Activity {
            return this@MainActivity
        }

        override fun onDownloadRequested(request: DownloadRequest) {
            Log.i(TAG, "onDownloadRequested url: ${request.url}")
            downloadLauncher.download(request)
        }

        override fun requestPermissions(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit) {
            launchForResult(ActivityResultContracts.RequestMultiplePermissions(), permissions, onResult)
        }

        override fun pickFile(intent: Intent, onResult: (resultCode: Int, data: Intent?) -> Unit): Boolean {
            val contract = ActivityResultContracts.StartActivityForResult()
            val callback = { result: ActivityResult -> onResult(result.resultCode, result.data) }
            try {
                launchForResult(contract, intent, callback)
            } catch (e: ActivityNotFoundException) {
                try {
                    //trying again with type */* (seems file pickers usually doesn't support specific types in intent filters but still can do the job)
                    intent.type = "*/*"
                    launchForResult(contract, intent, callback)
                } catch (e: ActivityNotFoundException) {
                    Utils.showToast(applicationContext, getString(R.string.err_cant_open_file_chooser))
                    return false
                }
            }
            return true
        }

        override fun onCopyTextToClipboardRequested(url: String) {
            val clipBoard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText("URL", url)
            clipBoard.setPrimaryClip(clipData)
            Toast.makeText(this@MainActivity, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }

        override fun onShareUrlRequested(url: String) {
            val share = Intent(Intent.ACTION_SEND)
            share.type = "text/plain"
            share.putExtra(Intent.EXTRA_SUBJECT, R.string.share_url)
            share.putExtra(Intent.EXTRA_TEXT, url)
            try {
                startActivity(share)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onOpenInExternalAppRequested(url: String) = openInExternalApp(url)

        override fun initiateVoiceSearch() {
            this@MainActivity.initiateVoiceSearch()
        }

        override fun onEditHomePageBookmarkSelected(index: Int) {
            lifecycleScope.launch {
                val bookmark = controller.homePageLinks.links.value.firstOrNull { it.order == index }
                var favoriteItem: FavoriteItem? = bookmark?.favoriteId?.let {
                    AppDatabase.db.favoritesDao().getById(it)
                }

                if (favoriteItem == null) {
                    favoriteItem = FavoriteItem()
                    favoriteItem.title = bookmark?.title
                    favoriteItem.url = bookmark?.url
                    favoriteItem.order = index
                    favoriteItem.homePageBookmark = true
                    onEditHomePageBookmark(favoriteItem)
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.bookmarks)
                        .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                            when (which) {
                                0 -> onEditHomePageBookmark(favoriteItem)
                                1 -> controller.modelScope.launch { controller.homePageLinks.remove(bookmark!!) }
                            }
                        }
                        .show()
                }
            }
        }

        override fun onPrepareForFullscreen() {
            //window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            //        WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            isFullscreen = true
        }

        override fun onExitFullscreen() {
            //window.setFlags(0, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            if (!config.keepScreenOn) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            isFullscreen = false
        }

        override fun onContextMenu(
            cursorDrawer: CursorDrawerDelegate,
            baseUri: String?,
            linkUri: String?,
            srcUri: String?,
            title: String?,
            altText: String?,
            textContent: String?,
            x: Int,
            y: Int
        ) {
            uiHandler.post {
                vb.vCursorMenu.show(
                    tab,this, cursorDrawer,
                    baseUri, linkUri, srcUri,
                    title, altText, textContent,
                    x, y,
                    backNavigationEventsAdapter
                )
            }
        }

        override fun suggestActionsForLink(baseUri: String?, linkUri: String?, srcUri: String?,
                                           title: String?, altText: String?, textContent: String?,
                                           x: Int, y: Int) {
            var s = linkUri ?: srcUri
            if (s != null && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1)
            }
            val url = s
            val isHTTPUrl = url != null && (url.startsWith("http://") || url.startsWith("https://"))
            val anchor = View(this@MainActivity)
            val lp = FrameLayout.LayoutParams(1, 1)
            lp.setMargins(x, y, 0, 0)
            vb.flWebViewContainer.addView(anchor, lp)
            linkActionsMenu = PopupMenu(this@MainActivity, anchor, Gravity.BOTTOM).also {
                it.inflate(R.menu.menu_link)
                it.menu.findItem(R.id.miOpenInNewTab).isVisible = isHTTPUrl
                it.menu.findItem(R.id.miOpenInExternalApp).isVisible = isHTTPUrl
                it.menu.findItem(R.id.miDownload).isVisible = isHTTPUrl
                it.menu.findItem(R.id.miCopyToClipboard).isVisible = url != null
                it.menu.findItem(R.id.miShare).isVisible = url != null
                it.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.miRefreshPage -> tab.webEngine.reload()
                        R.id.miOpenInNewTab -> openInNewTabAfterCurrent(url!!)
                        R.id.miOpenInExternalApp -> onOpenInExternalAppRequested(url!!)
                        R.id.miDownload -> onDownloadRequested(
                            DownloadRequest.forLink(url!!, tab.url, tab.webEngine.userAgentString, tab.webEngine.getCookies(url!!)))
                        R.id.miCopyToClipboard -> onCopyTextToClipboardRequested(url!!)
                        R.id.miShare -> onShareUrlRequested(url!!)
                    }
                    true
                }

                it.setOnDismissListener {
                    vb.flWebViewContainer.removeView(anchor)
                    linkActionsMenu = null
                }
                it.show()
            }
        }
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
