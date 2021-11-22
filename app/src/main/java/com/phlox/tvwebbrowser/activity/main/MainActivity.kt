package com.phlox.tvwebbrowser.activity.main

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.speech.RecognizerIntent
import android.transition.TransitionManager
import android.util.Log
import android.util.Patterns
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.downloads.ActiveDownloadsModel
import com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity
import com.phlox.tvwebbrowser.activity.history.HistoryActivity
import com.phlox.tvwebbrowser.activity.main.dialogs.FavoritesDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.SearchEngineConfigDialogFactory
import com.phlox.tvwebbrowser.activity.main.dialogs.settings.SettingsDialog
import com.phlox.tvwebbrowser.activity.main.view.CursorLayout
import com.phlox.tvwebbrowser.activity.main.view.Scripts
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx.Companion.HOME_URL
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.Listener
import com.phlox.tvwebbrowser.databinding.ActivityMainBinding
import com.phlox.tvwebbrowser.model.AndroidJSInterface
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.*
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import kotlinx.coroutines.*
import java.io.File
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.*

class MainActivity : AppCompatActivity() {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val VOICE_SEARCH_REQUEST_CODE = 10001
        private const val MY_PERMISSIONS_REQUEST_WEB_PAGE_PERMISSIONS = 10002
        private const val MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS = 10003
        const val MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 10004
        private const val PICKFILE_REQUEST_CODE = 10005
        private const val REQUEST_CODE_HISTORY_ACTIVITY = 10006
        const val REQUEST_CODE_UNKNOWN_APP_SOURCES = 10007
    }

    private lateinit var vb: ActivityMainBinding
    private lateinit var viewModel: MainActivityViewModel
    private lateinit var tabsModel: TabsModel
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var adblockModel: AdblockModel
    private lateinit var downloadsModel: ActiveDownloadsModel
    private lateinit var uiHandler: Handler
    private var running: Boolean = false
    private var downloadAnimation: Animation? = null
    private var fullScreenView: View? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var jsInterface: AndroidJSInterface

    @ExperimentalStdlibApi
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ActiveModelsRepository.get(MainActivityViewModel::class, this)
        settingsViewModel = ActiveModelsRepository.get(SettingsViewModel::class, this)
        adblockModel = ActiveModelsRepository.get(AdblockModel::class, this)
        downloadsModel = ActiveModelsRepository.get(ActiveDownloadsModel::class, this)
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

        vb.vTitles.listener = tabsListener

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

        if (Utils.isFireTV(this)) {
            vb.ibVoiceSearch.visibility = View.GONE
            vb.ibMenu.nextFocusRightId = R.id.ibHistory
        } else {
            vb.ibVoiceSearch.setOnClickListener { initiateVoiceSearch() }
        }

        vb.ibAdBlock.setOnClickListener { toggleAdBlockForTab() }
        vb.ibHome.setOnClickListener { navigate(HOME_URL) }
        vb.ibBack.setOnClickListener { navigateBack() }
        vb.ibForward.setOnClickListener {
            if (tabsModel.currentTab.value != null && (tabsModel.currentTab.value!!.webView?.canGoForward() == true)) {
                tabsModel.currentTab.value!!.webView?.goForward()
            }
        }
        vb.ibRefresh.setOnClickListener { refresh() }
        vb.ibCloseTab.setOnClickListener { tabsModel.currentTab.value?.apply { closeTab(this) } }

        vb.ibMenu.setOnClickListener { finish() }
        vb.ibDownloads.setOnClickListener { startActivity(Intent(this@MainActivity, DownloadsActivity::class.java)) }
        vb.ibFavorites.setOnClickListener { showFavorites() }
        vb.ibHistory.setOnClickListener { showHistory() }
        vb.ibSettings.setOnClickListener { showSettings() }
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

        vb.etUrl.onFocusChangeListener = etUrlFocusChangeListener

        vb.etUrl.setOnKeyListener(etUrlKeyListener)

        vb.llBottomPanel.childs.forEach {
            it.setOnTouchListener(bottomButtonsOnTouchListener)
            it.onFocusChangeListener = bottomButtonsFocusListener
            it.setOnKeyListener(bottomButtonsKeyListener)
        }

        settingsViewModel.uaString.subscribe(this.lifecycle) {
            for (tab in tabsModel.tabsStates) {
                tab.webView?.settings?.userAgentString = it
                if (tab.webView != null && (it == "")) {
                    settingsViewModel.saveUAString(SettingsViewModel.TV_BRO_UA_PREFIX +
                            tab.webView!!.settings.userAgentString.replace("Mobile Safari", "Safari"))
                }
            }
        }

        downloadsModel.activeDownloads.subscribe(this) {
            if (it.isNotEmpty()) {
                if (downloadAnimation == null) {
                    downloadAnimation = AnimationUtils.loadAnimation(this, R.anim.infinite_fadeinout_anim)
                    vb.ibDownloads.startAnimation(downloadAnimation)
                }
            } else {
                downloadAnimation?.apply {
                    this.reset()
                    vb.ibDownloads.clearAnimation()
                    downloadAnimation = null
                }
            }
        }

        viewModel.frequentlyUsedUrls.subscribe(this) {
            jsInterface.setSuggestions(application, it)
        }

        tabsModel.currentTab.subscribe(this) {
            vb.etUrl.setText(it?.url ?: "")
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

    private val etUrlFocusChangeListener = View.OnFocusChangeListener { _, focused ->
        if (focused) {
            if (vb.flUrl.parent == vb.rlActionBar) {
                syncTabWithTitles()
                vb.rlActionBar.removeView(vb.flUrl)
                val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                vb.rlRoot.addView(vb.flUrl, lp)
                vb.rlActionBar.visibility = View.INVISIBLE
                vb.llBottomPanel.visibility = View.INVISIBLE
                vb.ivMiniatures.visibility = View.INVISIBLE
                vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
                vb.flWebViewContainer.visibility = View.VISIBLE
                TransitionManager.beginDelayedTransition(vb.rlRoot)
            }

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(vb.etUrl, InputMethodManager.SHOW_FORCED)
            uiHandler.postDelayed(//workaround an android TV bug
                    {
                        vb.etUrl.selectAll()
                    }, 500)
        }
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

    @ExperimentalStdlibApi
    private val tabsListener = object : Listener {
        override fun onTitleChanged(index: Int) {
            Log.d(TAG, "onTitleChanged: $index")
            val tab = tabByTitleIndex(index)
            vb.etUrl.setText(tab?.url ?: "")
            uiHandler.removeCallbacks(displayThumbnailRunnable)
            displayThumbnailRunnable.tabState = tab
            uiHandler.postDelayed(displayThumbnailRunnable, 200)
        }

        override fun onTitleSelected(index: Int) {
            syncTabWithTitles()
            hideMenuOverlay()
        }

        override fun onAddNewTabSelected() {
            openInNewTab(HOME_URL, tabsModel.tabsStates.size)
        }

        override fun closeTab(tabState: WebTabState?) = this@MainActivity.closeTab(tabState)

        override fun openInNewTab(url: String, tabIndex: Int) = this@MainActivity.openInNewTab(url, tabIndex)
    }

    private fun showHistory() {
        startActivityForResult(
                Intent(this@MainActivity, HistoryActivity::class.java),
                REQUEST_CODE_HISTORY_ACTIVITY)
        hideMenuOverlay()
    }

    private fun showFavorites() {
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

    private val etUrlKeyListener = View.OnKeyListener { view, i, keyEvent ->
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
                    search(vb.etUrl.text.toString())
                    hideFloatAddressBar()
                    tabsModel.currentTab.value!!.webView?.requestFocus()
                }
                return@OnKeyListener true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    hideFloatAddressBar()
                }
                return@OnKeyListener true
            }
        }
        false
    }

    private fun hideFloatAddressBar() {
        if (vb.flUrl.parent == vb.rlRoot) {
            tabsModel.currentTab.value?.apply { vb.etUrl.setText(this.url) }
            vb.rlRoot.removeView(vb.flUrl)
            val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.addRule(RelativeLayout.END_OF, R.id.ibSettings)
            vb.rlActionBar.addView(vb.flUrl, lp)
            TransitionManager.beginDelayedTransition(vb.rlRoot)
        }
    }

    private fun tabByTitleIndex(index: Int) =
            if (index >= 0 && index < tabsModel.tabsStates.size) tabsModel.tabsStates[index] else null

    fun showSettings() {
        SettingsDialog(this, settingsViewModel).show()
    }

    fun navigateBack(goHomeIfNoHistory: Boolean = false) {
        if (tabsModel.currentTab.value != null && tabsModel.currentTab.value!!.webView?.canGoBack() == true) {
            tabsModel.currentTab.value!!.webView?.goBack()
        } else if (goHomeIfNoHistory) {
            navigate(HOME_URL)
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
                openInNewTab(HOME_URL)
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

        if ("" == settingsViewModel.searchEngineURL.value) {
            SearchEngineConfigDialogFactory.show(this@MainActivity, settingsViewModel, false,
                    object : SearchEngineConfigDialogFactory.Callback {
                        override fun onDone(url: String) {
                            if (settingsViewModel.needAutockeckUpdates &&
                                    settingsViewModel.updateChecker.versionCheckResult == null &&
                                    !settingsViewModel.lastUpdateNotificationTime.sameDay(Calendar.getInstance())) {
                                settingsViewModel.checkUpdate(false){
                                    if (settingsViewModel.updateChecker.hasUpdate()) {
                                        settingsViewModel.showUpdateDialogIfNeeded(this@MainActivity)
                                    }
                                }
                            }
                        }
                    })
        } else {
            val currentTab = tabsModel.currentTab.value
            if (currentTab == null || currentTab.url == HOME_URL) {
                showMenuOverlay()
            }
            if (settingsViewModel.needAutockeckUpdates &&
                    settingsViewModel.updateChecker.versionCheckResult == null &&
                    !settingsViewModel.lastUpdateNotificationTime.sameDay(Calendar.getInstance())) {
                settingsViewModel.checkUpdate(false){
                    if (settingsViewModel.updateChecker.hasUpdate()) {
                        settingsViewModel.showUpdateDialogIfNeeded(this@MainActivity)
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

    private fun openInNewTab(url: String?, index: Int = 0) {
        if (url == null) {
            return
        }
        val tab = WebTabState()
        tab.url = url
        createWebView(tab) ?: return
        tabsModel.tabsStates.add(index, tab)
        changeTab(tab)
        navigate(url)
        if (vb.rlActionBar.visibility == View.VISIBLE) {
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
            tabsModel.tabsStates.size == 1 -> openInNewTab(HOME_URL, 0)

            position > 0 -> changeTab(tabsModel.tabsStates[position - 1])

            else -> changeTab(tabsModel.tabsStates[position + 1])
        }
        tabsModel.onCloseTab(tab)
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

        if (settingsViewModel.uaString.value == null || settingsViewModel.uaString.value == "") {
            settingsViewModel.saveUAString("TV Bro/1.0 " + webView.settings.userAgentString.replace("Mobile Safari", "Safari"))
        }
        webView.settings.userAgentString = settingsViewModel.uaString.value

        webView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && vb.flUrl.parent == vb.rlRoot) {
                hideFloatAddressBar()
            }
        }

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
            REQUEST_CODE_UNKNOWN_APP_SOURCES -> if (settingsViewModel.needToShowUpdateDlgAgain) {
                settingsViewModel.showUpdateDialogIfNeeded(this)
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
            tabsModel.saveTab(this, true)
        }

        super.onPause()
        running = false
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
        vb.etUrl.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.default_url_color))
        if (tabsModel.currentTab.value != null) {
            tabsModel.currentTab.value!!.webView?.loadUrl(url)
        } else {
            openInNewTab(url)
        }
    }

    fun search(text: String) {
        @Suppress("NAME_SHADOWING") var text = text
        val trimmedLowercased = text.trim { it <= ' ' }.toLowerCase(Locale.ROOT)
        if (Patterns.WEB_URL.matcher(text).matches() || trimmedLowercased.startsWith("http://") || trimmedLowercased.startsWith("https://")) {
            if (!text.toLowerCase(Locale.ROOT).contains("://")) {
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

            val searchUrl = settingsViewModel.searchEngineURL.value!!.replace("[query]", query!!)
            navigate(searchUrl)
        }
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
        } else if (keyCode == KeyEvent.KEYCODE_BACK && vb.flUrl.parent == vb.rlRoot) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { hideFloatAddressBar() }
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
                    vb.ibMenu.requestFocus()
                }
                .start()

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
                /*.withEndAction {
                    vMiniatures.layoutParams = vMiniatures.layoutParams.apply {
                        (this as RelativeLayout.LayoutParams).setMargins(0, rlActionBar.height, 0, 0)
                    }
                    vMiniatures.translationY = 0f
                }*/
                .start()
    }

    private fun displayThumbnail(currentTab: WebTabState?) {
        if (currentTab != null) {
            if (tabByTitleIndex(vb.vTitles.current) != currentTab) return
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
                    if (hideBottomButtons && tabsModel.currentTab.value != null && vb.flUrl.parent != vb.rlRoot) {
                        tabsModel.currentTab.value!!.webView?.requestFocus()
                    }
                }
                .start()
    }

    private fun syncTabWithTitles() {
        val tab = tabByTitleIndex(vb.vTitles.current)
        if (tab == null) {
            openInNewTab(HOME_URL, if (vb.vTitles.current < 0) 0 else tabsModel.tabsStates.size)
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

    fun initiateVoiceSearch() {
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
            vb.vTitles.onTabTitleUpdated(tab)
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
            vb.vTitles.onFavIconUpdated(tab)
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
            if (tabByTitleIndex(vb.vTitles.current) == tab) {
                vb.etUrl.setText(tab.url)
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
            if (tabByTitleIndex(vb.vTitles.current) == tab) {
                vb.etUrl.setText(tab.url)
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
            if (HOME_URL == url) {
                tab.webView?.loadUrl("javascript:renderSuggestions()")
            }
        }

        override fun onPageCertificateError(url: String?) {
            vb.etUrl.setTextColor(Color.RED)
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
            val tab = WebTabState()
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
