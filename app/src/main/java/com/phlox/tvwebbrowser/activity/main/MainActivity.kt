package com.phlox.tvwebbrowser.activity.main

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Process
import android.speech.RecognizerIntent
import android.util.Log
import android.util.Patterns
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity
import com.phlox.tvwebbrowser.activity.history.HistoryActivity
import com.phlox.tvwebbrowser.activity.main.dialogs.SearchEngineConfigDialogFactory
import com.phlox.tvwebbrowser.activity.main.dialogs.favorites.FavoritesDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.settings.SettingsDialog
import com.phlox.tvwebbrowser.activity.main.view.ActionBar
import com.phlox.tvwebbrowser.activity.main.view.CursorLayout
import com.phlox.tvwebbrowser.activity.main.view.Scripts
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.Listener
import com.phlox.tvwebbrowser.databinding.ActivityMainBinding
import com.phlox.tvwebbrowser.model.AndroidJSInterface
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.*
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import java.io.File
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.*


open class MainActivity : AppCompatActivity(), ActionBar.Callback {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val VOICE_SEARCH_REQUEST_CODE = 10001
        private const val MY_PERMISSIONS_REQUEST_WEB_PAGE_PERMISSIONS = 10002
        private const val MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS = 10003
        const val MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 10004
        private const val PICKFILE_REQUEST_CODE = 10005
        private const val REQUEST_CODE_HISTORY_ACTIVITY = 10006
        const val REQUEST_CODE_UNKNOWN_APP_SOURCES = 10007
        const val KEY_PROCESS_ID_TO_KILL = "proc_id_to_kill"
    }

    private lateinit var vb: ActivityMainBinding
    private lateinit var viewModel: MainActivityViewModel
    private lateinit var tabsModel: TabsModel
    private lateinit var settingsModel: SettingsModel
    private lateinit var adblockModel: AdblockModel
    private lateinit var uiHandler: Handler
    private var running: Boolean = false
    private var fullScreenView: View? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var jsInterface: AndroidJSInterface
    private val config = TVBro.config

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incognitoMode = config.incognitoMode
        if (incognitoMode xor (this is IncognitoModeMainActivity)) {
            switchProcess(incognitoMode)
            finish()
        }
        val pidToKill = intent?.getIntExtra(KEY_PROCESS_ID_TO_KILL, -1) ?: -1
        if (pidToKill != -1) {
            Process.killProcess(pidToKill)
        }

        viewModel = ActiveModelsRepository.get(MainActivityViewModel::class, this)
        settingsModel = ActiveModelsRepository.get(SettingsModel::class, this)
        adblockModel = ActiveModelsRepository.get(AdblockModel::class, this)
        tabsModel = ActiveModelsRepository.get(TabsModel::class, this)
        jsInterface = AndroidJSInterface(viewModel, tabsModel)
        uiHandler = Handler()
        prefs = getSharedPreferences(TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)
        AndroidBug5497Workaround.assistActivity(this)

        vb.ivMiniatures.visibility = View.INVISIBLE
        vb.llBottomPanel.visibility = View.INVISIBLE
        vb.rlActionBar.visibility = View.INVISIBLE
        vb.progressBar.visibility = View.GONE

        vb.vTabs.listener = tabsListener

        vb.flWebViewContainer.setCallback(object : CursorLayout.Callback {
            override fun onUserInteraction() {
                val tab = tabsModel.currentTab.value
                if (tab != null) {
                    if (!tab.webPageInteractionDetected) {
                        tab.webPageInteractionDetected = true
                        viewModel.logVisitedHistory(tab.title, tab.url, tab.faviconHash)
                    }
                }
            }
        })

        vb.ibAdBlock.setOnClickListener { toggleAdBlockForTab() }
        vb.ibHome.setOnClickListener { navigate(settingsModel.homePage.value) }
        vb.ibBack.setOnClickListener { navigateBack() }
        vb.ibForward.setOnClickListener {
            if (tabsModel.currentTab.value != null && (tabsModel.currentTab.value!!.webView?.canGoForward() == true)) {
                tabsModel.currentTab.value!!.webView?.goForward()
            }
        }
        vb.ibRefresh.setOnClickListener { refresh() }
        vb.ibCloseTab.setOnClickListener { tabsModel.currentTab.value?.apply { closeTab(this) } }

        vb.vActionBar.callback = this

        vb.ibZoomIn.setOnClickListener {
            val tab = tabsModel.currentTab.value ?: return@setOnClickListener
            tab.webView?.apply {
                if (this.canZoomIn()) {
                    tab.changingScale = true
                    this.zoomIn()
                }
                onWebViewUpdated(tab)
                if (!this.canZoomIn()) {
                    vb.ibZoomOut.requestFocus()
                }
            }
        }
        vb.ibZoomOut.setOnClickListener {
            val tab = tabsModel.currentTab.value ?: return@setOnClickListener
            tab.webView?.apply {
                if (this.canZoomOut()) {
                    tab.changingScale = true
                    this.zoomOut()
                }
                onWebViewUpdated(tab)
                if (!this.canZoomOut()) {
                    vb.ibZoomIn.requestFocus()
                }
            }
        }

        vb.llBottomPanel.childs.forEach {
            it.setOnTouchListener(bottomButtonsOnTouchListener)
            it.onFocusChangeListener = bottomButtonsFocusListener
            it.setOnKeyListener(bottomButtonsKeyListener)
        }

        settingsModel.uaString.subscribe(this.lifecycle) {
            for (tab in tabsModel.tabsStates) {
                tab.webView?.settings?.userAgentString = it
                if (tab.webView != null && (it == "")) {
                    settingsModel.saveUAString(SettingsModel.TV_BRO_UA_PREFIX +
                            tab.webView!!.settings.userAgentString.replace("Mobile Safari", "Safari"))
                }
            }
        }

        settingsModel.keepScreenOn.subscribe(this.lifecycle) {
            if (it) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        viewModel.frequentlyUsedUrls.subscribe(this) {
            jsInterface.setSuggestions(application, it)
        }

        tabsModel.currentTab.subscribe(this) {
            vb.vActionBar.setAddressBoxText(it?.url ?: "")
            it?.let {
                onWebViewUpdated(it)
            }
        }

        tabsModel.tabsStates.subscribe(this, false) {
            if (it.isEmpty()) {
                vb.flWebViewContainer.removeAllViews()
            }
        }

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

    private var mConnectivityChangeReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetworkInfo
            val isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting
            if (tabsModel.currentTab.value != null) {
                tabsModel.currentTab.value!!.webView?.setNetworkAvailable(isConnected)
            }
        }
    }

    private val displayThumbnailRunnable = object : Runnable {
        var tabState: WebTabState? = null
        override fun run() {
            tabState?.let { displayThumbnail(it) }
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
            openInNewTab(settingsModel.homePage.value, tabsModel.tabsStates.size)
        }

        override fun closeTab(tabState: WebTabState?) = this@MainActivity.closeTab(tabState)

        override fun openInNewTab(url: String, tabIndex: Int) = this@MainActivity.openInNewTab(url, tabIndex, false)
    }

    override fun closeWindow() {
        finish()
    }

    override fun showDownloads() {
        startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
    }

    override fun showHistory() {
        startActivityForResult(
                Intent(this@MainActivity, HistoryActivity::class.java),
                REQUEST_CODE_HISTORY_ACTIVITY)
        hideMenuOverlay()
    }

    override fun showFavorites() {
        val currentTab = tabsModel.currentTab.value
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
                    tabsModel.currentTab.value?.webView?.requestFocus()
                    vb.flWebViewContainer.cursorPosition
                }
                return@OnKeyListener true
            }
        }
        false
    }

    private fun tabByTitleIndex(index: Int) =
            if (index >= 0 && index < tabsModel.tabsStates.size) tabsModel.tabsStates[index] else null

    override fun showSettings() {
        SettingsDialog(this, settingsModel).show()
    }

    override fun onExtendedAddressBarMode() {
        vb.llBottomPanel.visibility = View.INVISIBLE
        //vb.ivMiniatures.visibility = View.INVISIBLE
        //vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
        //vb.flWebViewContainer.visibility = View.VISIBLE
        //TransitionManager.beginDelayedTransition(vb.rlRoot)
    }

    override fun onUrlInputDone() {
        hideMenuOverlay()
    }

    fun navigateBack(goHomeIfNoHistory: Boolean = false) {
        if (tabsModel.currentTab.value != null && tabsModel.currentTab.value!!.webView?.canGoBack() == true) {
            tabsModel.currentTab.value!!.webView?.goBack()
        } else if (goHomeIfNoHistory) {
            navigate(settingsModel.homePage.value)
        } else if (vb.rlActionBar.visibility != View.VISIBLE) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    fun refresh() {
        tabsModel.currentTab.value?.webView?.reload()
    }

    override fun onDestroy() {
        jsInterface.setActivity(null)
        tabsModel.onDetachActivity()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val intentUri = intent.data
        if (intentUri != null) {
            openInNewTab(intentUri.toString())
        }
    }

    private fun loadState() = lifecycleScope.launch(Dispatchers.Main) {
        vb.progressBarGeneric.visibility = View.VISIBLE
        vb.progressBarGeneric.requestFocus()
        viewModel.loadState().join()
        tabsModel.loadState().join()

        if (!running) {
            return@launch
        }

        vb.progressBarGeneric.visibility = View.GONE

        val intentUri = intent.data
        if (intentUri == null) {
            if (tabsModel.tabsStates.isEmpty()) {
                openInNewTab(settingsModel.homePage.value)
            } else {
                var foundSelectedTab = false
                for (i in tabsModel.tabsStates.indices) {
                    val tab = tabsModel.tabsStates[i]
                    if (tab.selected) {
                        changeTab(tab)
                        foundSelectedTab = true
                        break
                    }
                }
                if (!foundSelectedTab) {//this may happen in some error states
                    changeTab(tabsModel.tabsStates[0])
                }
            }
        } else {
            openInNewTab(intentUri.toString())
        }

        if ("" == settingsModel.searchEngineURL.value) {
            SearchEngineConfigDialogFactory.show(this@MainActivity, settingsModel, false,
                    object : SearchEngineConfigDialogFactory.Callback {
                        override fun onDone(url: String) {
                            if (settingsModel.needAutockeckUpdates &&
                                    settingsModel.updateChecker.versionCheckResult == null &&
                                    !settingsModel.lastUpdateNotificationTime.sameDay(Calendar.getInstance())) {
                                settingsModel.checkUpdate(false){
                                    if (settingsModel.updateChecker.hasUpdate()) {
                                        settingsModel.showUpdateDialogIfNeeded(this@MainActivity)
                                    }
                                }
                            }
                        }
                    })
        } else {
            val currentTab = tabsModel.currentTab.value
            if (currentTab == null || currentTab.url == settingsModel.homePage.value) {
                showMenuOverlay()
            }
            if (settingsModel.needAutockeckUpdates &&
                    settingsModel.updateChecker.versionCheckResult == null &&
                    !settingsModel.lastUpdateNotificationTime.sameDay(Calendar.getInstance())) {
                settingsModel.checkUpdate(false){
                    if (settingsModel.updateChecker.hasUpdate()) {
                        settingsModel.showUpdateDialogIfNeeded(this@MainActivity)
                    }
                }
            }
        }

        if (Utils.isFireTV(this@MainActivity)) {
            //amazon blocks some downloads, this is workaround
            viewModel.logCatOutput.subscribe(this@MainActivity) {
                logMessage ->
                if (logMessage.endsWith("AwContentsClientBridge: Dropping new download request.")) {
                    tabsModel.currentTab.value?.apply {
                        val url = this.lastLoadingUrl ?: return@apply
                        onDownloadRequested(url, this)
                    }
                }
            }
        }
    }

    private fun openInNewTab(url: String?, index: Int = 0, needToHideMenuOverlay: Boolean = true) {
        if (url == null) {
            return
        }
        val tab = WebTabState(url = url, incognito = config.incognitoMode)
        createWebView(tab) ?: return
        tabsModel.tabsStates.add(index, tab)
        changeTab(tab)
        navigate(url)
        if (needToHideMenuOverlay && vb.rlActionBar.visibility == View.VISIBLE) {
            hideMenuOverlay(true)
        }
    }

    private fun closeTab(tab: WebTabState?) {
        if (tab == null) return
        val position = tabsModel.tabsStates.indexOf(tab)
        if (tabsModel.currentTab.value == tab) {
            tabsModel.currentTab.value = null
        }
        tab.webView?.apply { vb.flWebViewContainer.removeView(this) }
        when {
            tabsModel.tabsStates.size == 1 -> openInNewTab(settingsModel.homePage.value, 0)

            position > 0 -> changeTab(tabsModel.tabsStates[position - 1])

            else -> changeTab(tabsModel.tabsStates[position + 1])
        }
        tabsModel.onCloseTab(tab)
        hideMenuOverlay(true)
        hideBottomPanel()
    }

    private fun changeTab(newTab: WebTabState) {
        tabsModel.changeTab(newTab, { tab: WebTabState -> createWebView(tab) }, vb.flWebViewContainer)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: WebTabState): WebViewEx? {
        val webView: WebViewEx
        try {
            webView = WebViewEx(this, WebViewCallback(tab), jsInterface)
            tab.webView = webView
        } catch (e: Throwable) {
            e.printStackTrace()

            val dialogBuilder = AlertDialog.Builder(this)
                    .setTitle(R.string.error)
                    .setCancelable(false)
                    .setMessage(R.string.err_webview_can_not_link)
                    .setNegativeButton(R.string.exit) { _, _ -> finish() }

            val appPackageName = "com.google.android.webview"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName"))
            val activities = packageManager.queryIntentActivities(intent, 0)
            if (activities.size > 0) {
                dialogBuilder.setPositiveButton(R.string.find_in_apps_store) { _, _ ->
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    finish()
                }
            }
            dialogBuilder.show()
            return null
        }

        if (settingsModel.uaString.value.isBlank()) {
            settingsModel.saveUAString("TV Bro/1.0 " + webView.settings.userAgentString.replace("Mobile Safari", "Safari"))
        }
        webView.settings.userAgentString = settingsModel.uaString.value

        return webView
    }

    private fun onDownloadRequested(url: String, tab: WebTabState) {
        Log.i(TAG, "onDownloadRequested url: $url")
        val fileName = Uri.parse(url).lastPathSegment
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
        onDownloadRequested(url, tab.url ?: "", fileName
                ?: "url.html", tab.webView?.settings?.userAgentString
                ?: getString(R.string.app_name), mimeType)
    }

    private fun onWebViewUpdated(tab: WebTabState) {
        vb.ibBack.isEnabled = tab.webView?.canGoBack() == true
        vb.ibForward.isEnabled = tab.webView?.canGoForward() == true
        val zoomPossible = tab.webView?.canZoomIn() == true || tab.webView?.canZoomOut() == true
        vb.ibZoomIn.visibility = if (zoomPossible) View.VISIBLE else View.GONE
        vb.ibZoomOut.visibility = if (zoomPossible) View.VISIBLE else View.GONE
        vb.ibZoomIn.isEnabled = tab.webView?.canZoomIn() == true
        vb.ibZoomOut.isEnabled = tab.webView?.canZoomOut() == true
        val adblockEnabled = tab.adblock ?: adblockModel.adBlockEnabled
        vb.ibAdBlock.setImageResource(if (adblockEnabled) R.drawable.ic_adblock_on else R.drawable.ic_adblock_off)
        vb.tvBlockedAdCounter.visibility = if (adblockEnabled && tab.webView?.blockedAds != 0) View.VISIBLE else View.GONE
        vb.tvBlockedAdCounter.text = tab.webView?.blockedAds?.toString() ?: ""
    }

    private fun onDownloadRequested(url: String, referer: String, originalDownloadFileName: String, userAgent: String, mimeType: String?,
                                    operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP) {
        viewModel.onDownloadRequested(this, url, referer, originalDownloadFileName, userAgent, mimeType, operationAfterDownload, null)
    }

    override fun onTrimMemory(level: Int) {
        for (tab in tabsModel.tabsStates) {
            if (!tab.selected) {
                tab.recycleWebView()
            }
        }
        super.onTrimMemory(level)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.isEmpty()) return
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_WEB_PAGE_PERMISSIONS -> {
                tabsModel.currentTab.value?.webView?.onPermissionsResult(permissions, grantResults, false)
                return
            }
            MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS -> {
                tabsModel.currentTab.value?.webView?.onPermissionsResult(permissions, grantResults, true)
                return
            }
            MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startDownload(this)
                }
            }
            else -> {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            VOICE_SEARCH_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    // Populate the wordsList with the String values the recognition engine thought it heard
                    val matches = data?.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS)
                    if (matches == null || matches.isEmpty()) {
                        Utils.showToast(this, getString(R.string.can_not_recognize))
                        return
                    }
                    search(matches[0])
                    hideMenuOverlay()
                }
            }
            PICKFILE_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK && data != null ) {
                    tabsModel.currentTab.value?.webView?.onFilePicked(data)
                }
            }
            REQUEST_CODE_HISTORY_ACTIVITY -> if (resultCode == Activity.RESULT_OK) {
                val url = data?.getStringExtra(HistoryActivity.KEY_URL)
                if (url != null) {
                    navigate(url)
                }
                hideMenuOverlay()
            }
            REQUEST_CODE_UNKNOWN_APP_SOURCES -> if (settingsModel.needToShowUpdateDlgAgain) {
                settingsModel.showUpdateDialogIfNeeded(this)
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onResume() {
        running = true
        super.onResume()
        jsInterface.setActivity(this)
        val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        registerReceiver(mConnectivityChangeReceiver, intentFilter)
        if (tabsModel.currentTab.value != null) {
            tabsModel.currentTab.value!!.webView?.onResume()
        }
    }

    override fun onPause() {
        jsInterface.setActivity(null)
        if (mConnectivityChangeReceiver != null) unregisterReceiver(mConnectivityChangeReceiver)
        tabsModel.currentTab.value?.apply {
            webView?.onPause()
            onPause()
            tabsModel.saveTab(this)
        }

        super.onPause()
        running = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private fun toggleAdBlockForTab() {
        tabsModel.currentTab.value?.apply {
            val currentState = adblock ?: adblockModel.adBlockEnabled
            val newState = !currentState
            adblock = newState
            webView?.onUpdateAdblockSetting(newState)
            onWebViewUpdated(this)
            refresh()
        }
    }

    fun navigate(url: String) {
        vb.vActionBar.setAddressBoxTextColor(ContextCompat.getColor(this@MainActivity, R.color.default_url_color))
        if (tabsModel.currentTab.value != null) {
            tabsModel.currentTab.value!!.webView?.loadUrl(url)
        } else {
            openInNewTab(url)
        }
    }

    override fun search(aText: String) {
        var text = aText
        val trimmedLowercased = text.trim { it <= ' ' }.lowercase(Locale.ROOT)
        if (Patterns.WEB_URL.matcher(text).matches() || trimmedLowercased.startsWith("http://") || trimmedLowercased.startsWith("https://")) {
            if (!text.lowercase(Locale.ROOT).contains("://")) {
                text = "https://$text"
            }
            navigate(text)
        } else {
            var query: String? = null
            try {
                query = URLEncoder.encode(text, "utf-8")
            } catch (e1: UnsupportedEncodingException) {
                e1.printStackTrace()
                Utils.showToast(this, R.string.error)
                return
            }

            val searchUrl = settingsModel.searchEngineURL.value.replace("[query]", query!!)
            navigate(searchUrl)
        }
    }

    override fun toggleIncognitoMode() {
        lifecycleScope.launch(Dispatchers.Main) {
            val becomingIncognitoMode = !config.incognitoMode
            if (!becomingIncognitoMode) {
                tabsModel.onCloseAllTabs().join()
                tabsModel.currentTab.value = null
                viewModel.clearIncognitoData().join()
            }
            config.incognitoMode = becomingIncognitoMode
            switchProcess(becomingIncognitoMode)
        }
    }

    private fun switchProcess(incognitoMode: Boolean) {
        val activityClass = if (incognitoMode) IncognitoModeMainActivity::class.java
        else MainActivity::class.java
        val intent = Intent(this@MainActivity, activityClass)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra(KEY_PROCESS_ID_TO_KILL, Process.myPid())
        startActivity(intent)
        finish()
    }

    fun toggleMenu() {
        if (vb.rlActionBar.visibility == View.INVISIBLE) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val shortcutMgr = ShortcutMgr.getInstance()
        val keyCode = if (event.keyCode != 0) event.keyCode else event.scanCode

        if (keyCode == KeyEvent.KEYCODE_BACK && fullScreenView != null) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post {
                    tabsModel.currentTab.value?.webView?.onHideCustomView()
                }
            }
            return true
        } else if (keyCode == KeyEvent.KEYCODE_BACK && vb.llBottomPanel.visibility == View.VISIBLE && vb.rlActionBar.visibility != View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { hideBottomPanel() }
            }
            return true
        } else if (keyCode == KeyEvent.KEYCODE_BACK && vb.flWebViewContainer.fingerMode) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { vb.flWebViewContainer.exitFingerMode() }
            }
            return true
        } else if (shortcutMgr.canProcessKeyCode(keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { shortcutMgr.process(keyCode, this) }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showMenuOverlay() {
        vb.ivMiniatures.visibility = View.VISIBLE
        vb.llBottomPanel.visibility = View.VISIBLE
        vb.flWebViewContainer.visibility = View.INVISIBLE
        val currentTab = tabsModel.currentTab.value
        if (currentTab != null) {
            currentTab.thumbnail = currentTab.webView?.renderThumbnail(currentTab.thumbnail)
            displayThumbnail(currentTab)
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

    private fun displayThumbnail(currentTab: WebTabState?) {
        if (currentTab != null) {
            if (tabByTitleIndex(vb.vTabs.current) != currentTab) return
            vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
            vb.ivMiniatures.visibility = View.VISIBLE
            if (currentTab.thumbnail != null) {
                vb.ivMiniatures.setImageBitmap(currentTab.thumbnail)
            } else if (currentTab.thumbnailHash != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val thumbnail = currentTab.loadThumbnail()
                    launch(Dispatchers.Main) {
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
                    if (hideBottomButtons && tabsModel.currentTab.value != null) {
                        tabsModel.currentTab.value!!.webView?.requestFocus()
                    }
                }
                .start()
    }

    private fun syncTabWithTitles() {
        val tab = tabByTitleIndex(vb.vTabs.current)
        if (tab == null) {
            openInNewTab(settingsModel.homePage.value, if (vb.vTabs.current < 0) 0 else tabsModel.tabsStates.size)
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

    fun onDownloadStarted(fileName: String) {
        Utils.showToast(this, getString(R.string.download_started,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + File.separator + fileName))
        showMenuOverlay()
    }

    override fun initiateVoiceSearch() {
        hideMenuOverlay()
        VoiceSearchHelper.initiateVoiceSearch(this, VOICE_SEARCH_REQUEST_CODE)
    }

    private inner class WebViewCallback(val tab: WebTabState): WebViewEx.Callback {
        override fun getActivity(): Activity {
            return this@MainActivity
        }

        override fun onOpenInNewTabRequested(s: String) {
            var index = tabsModel.tabsStates.indexOf(tabsModel.currentTab.value)
            index = if (index == -1) tabsModel.tabsStates.size else index + 1
            openInNewTab(s, index)
        }

        override fun onDownloadRequested(url: String) {
            onDownloadRequested(url, tab)
        }

        override fun onLongTap() {
            vb.flWebViewContainer?.goToFingerMode()
        }

        override fun onThumbnailError() {
            //nop for now
        }

        override fun onShowCustomView(view: View) {
            tab.webView?.visibility = View.GONE
            vb.flFullscreenContainer.visibility = View.VISIBLE
            vb.flFullscreenContainer.addView(view)
            vb.flFullscreenContainer.cursorPosition.set(vb.flWebViewContainer.cursorPosition)
            fullScreenView = view
        }

        override fun onHideCustomView() {
            if (fullScreenView != null) {
                vb.flFullscreenContainer.removeView(fullScreenView)
                fullScreenView = null
            }
            vb.flWebViewContainer.cursorPosition.set(vb.flFullscreenContainer.cursorPosition)
            vb.flFullscreenContainer.visibility = View.INVISIBLE
            tab.webView?.visibility = View.VISIBLE
        }

        override fun onProgressChanged(newProgress: Int) {
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

        override fun onReceivedTitle(title: String) {
            tab.title = title
            vb.vTabs.onTabTitleUpdated(tab)
        }

        override fun requestPermissions(array: Array<String>, geo: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(array, if (geo) MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS else MY_PERMISSIONS_REQUEST_WEB_PAGE_PERMISSIONS)
            }
        }

        override fun onShowFileChooser(intent: Intent): Boolean {
            try {
                startActivityForResult(intent, PICKFILE_REQUEST_CODE)
            } catch (e: ActivityNotFoundException) {
                try {
                    //trying again with type */* (seems file pickers usually doesn't support specific types in intent filters but still can do the job)
                    intent.type = "*/*"
                    startActivityForResult(intent, PICKFILE_REQUEST_CODE)
                } catch (e: ActivityNotFoundException) {
                    Utils.showToast(applicationContext, getString(R.string.err_cant_open_file_chooser))
                    return false
                }
            }
            return true
        }

        override fun onReceivedIcon(icon: Bitmap) {
            tab.updateFavIcon(this@MainActivity, icon)
            vb.vTabs.onFavIconUpdated(tab)
        }

        override fun shouldOverrideUrlLoading(url: String): Boolean {
            tab.lastLoadingUrl = url

            if (URLUtil.isNetworkUrl(url)) {
                return false
            }

            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)

            intent.putExtra("URL_INTENT_ORIGIN", tab.webView?.hashCode())
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null

            if (intent.resolveActivity(this@MainActivity.packageManager) != null) {
                startActivityIfNeeded(intent, -1)

                return true
            }

            return false
        }

        override fun onPageStarted(url: String?) {
            onWebViewUpdated(tab)
            val webViewUrl = tab.webView?.url
            if (webViewUrl != null) {
                tab.url = webViewUrl
            } else if (url != null) {
                tab.url = url
            }
            if (tabByTitleIndex(vb.vTabs.current) == tab) {
                vb.vActionBar.setAddressBoxText(tab.url)
            }
            tab.hasAutoOpenedWindows = false
        }

        override fun onPageFinished(url: String?) {
            if (tab.webView == null || tabsModel.currentTab.value == null) {
                return
            }
            onWebViewUpdated(tab)

            val webViewUrl = tab.webView?.url
            if (webViewUrl != null) {
                tab.url = webViewUrl
            } else if (url != null) {
                tab.url = url
            }
            if (tabByTitleIndex(vb.vTabs.current) == tab) {
                vb.vActionBar.setAddressBoxText(tab.url)
            }

            //thumbnail
            tabsModel.tabsStates.onEach { if (it != tab) it.thumbnail = null }
            val newThumbnail = tab.webView?.renderThumbnail(tab.thumbnail)
            if (newThumbnail != null) {
                tab.updateThumbnail(this@MainActivity, newThumbnail, lifecycleScope)
                if (vb.rlActionBar.visibility == View.VISIBLE && tab == tabsModel.currentTab.value) {
                    displayThumbnail(tab)
                }
            }

            tab.webView?.evaluateJavascript(Scripts.INITIAL_SCRIPT, null)
            tab.webPageInteractionDetected = false
            if (settingsModel.homePage.value == url) {
                tab.webView?.loadUrl("javascript:renderSuggestions()")
            }
        }

        override fun onPageCertificateError(url: String?) {
            vb.vActionBar.setAddressBoxTextColor(Color.RED)
        }

        override fun isAd(request: WebResourceRequest, baseUri: Uri): Boolean {
            return adblockModel.isAd(request, baseUri)
        }

        override fun isAdBlockingEnabled(): Boolean {
            tabsModel.currentTab.value?.adblock?.apply {
                return this
            }
            return  adblockModel.adBlockEnabled
        }

        override fun onBlockedAdsCountChanged(blockedAds: Int) {
            if (!adblockModel.adBlockEnabled) return
            vb.tvBlockedAdCounter.visibility = if (blockedAds > 0) View.VISIBLE else View.GONE
            vb.tvBlockedAdCounter.text = blockedAds.toString()
        }

        override fun onCreateWindow(dialog: Boolean, userGesture: Boolean): WebViewEx? {
            if (isAdBlockingEnabled() && tab.hasAutoOpenedWindows) {
                tab.webView?.apply {
                    blockedAds++
                    onBlockedAdsCountChanged(blockedAds)
                }
                return null
            }
            val tab = WebTabState(incognito = config.incognitoMode)
            val webView = createWebView(tab) ?: return null
            val currentTab = this@MainActivity.tabsModel.currentTab.value ?: return null
            val index = tabsModel.tabsStates.indexOf(currentTab) + 1
            tabsModel.tabsStates.add(index, tab)
            changeTab(tab)
            this.tab.hasAutoOpenedWindows = true
            return webView
        }

        override fun closeWindow(window: WebView) {
            for (tab in tabsModel.tabsStates) {
                if (tab.webView == window) {
                    closeTab(tab)
                    break
                }
            }
        }

        override fun onDownloadStart(url: String, userAgent: String, contentDisposition: String, mimetype: String?, contentLength: Long) {
            Log.i(TAG, "DownloadListener.onDownloadStart url: $url")
            onDownloadRequested(url, tab.url
                    ?: "", DownloadUtils.guessFileName(url, contentDisposition, mimetype), userAgent
                    ?: tab.webView?.settings?.userAgentString
                    ?: getString(R.string.app_name), mimetype)
        }

        override fun onScaleChanged(oldScale: Float, newScale: Float) {
            Log.d(TAG, "onScaleChanged: oldScale: $oldScale newScale: $newScale")
            val tabScale = tab.scale
            if (tab.changingScale) {
                tab.changingScale = false
                tab.scale = newScale
            } else if (tabScale != null && tabScale != newScale) {
                val zoomBy = tabScale / newScale
                Log.d(TAG, "Auto zoom by: $zoomBy")
                tab.changingScale = true
                tab.webView?.zoomBy(zoomBy)
            }
        }
    }
}
