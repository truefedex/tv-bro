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
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity
import com.phlox.tvwebbrowser.activity.history.HistoryActivity
import com.phlox.tvwebbrowser.activity.main.dialogs.FavoritesDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.SearchEngineConfigDialogFactory
import com.phlox.tvwebbrowser.activity.main.dialogs.settings.SettingsDialog
import com.phlox.tvwebbrowser.activity.main.view.CursorLayout
import com.phlox.tvwebbrowser.activity.main.view.Scripts
import com.phlox.tvwebbrowser.activity.main.view.TitlesView
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx.Companion.HOME_URL
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import java.io.File
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.*
import kotlin.collections.ArrayList


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

    private lateinit var viewModel: MainActivityViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var adblockViewModel: AdblockViewModel
    private lateinit var uiHandler: Handler
    private var running: Boolean = false
    private var downloadsService: DownloadService? = null
    private var downloadAnimation: Animation? = null
    private var fullScreenView: View? = null
    private lateinit var prefs: SharedPreferences

    @ExperimentalStdlibApi
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(MainActivityViewModel::class.java)
        settingsViewModel = ViewModelProvider(this).get(SettingsViewModel::class.java)
        adblockViewModel = ViewModelProvider(this).get(AdblockViewModel::class.java)
        uiHandler = Handler()
        prefs = getSharedPreferences(TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        setContentView(R.layout.activity_main)
        AndroidBug5497Workaround.assistActivity(this)

        ivMiniatures.visibility = View.INVISIBLE
        llBottomPanel.visibility = View.INVISIBLE
        rlActionBar.visibility = View.INVISIBLE
        progressBar.visibility = View.GONE

        vTitles.listener = tabsListener

        flWebViewContainer.setCallback(object : CursorLayout.Callback {
            override fun onUserInteraction() {
                val tab = viewModel.currentTab.value
                if (tab != null) {
                    if (!tab.webPageInteractionDetected) {
                        tab.webPageInteractionDetected = true
                        viewModel.logVisitedHistory(tab.title, tab.url, tab.faviconHash)
                    }
                }
            }
        })

        if (Utils.isFireTV(this)) {
            ibVoiceSearch.visibility = View.GONE
            ibMenu.nextFocusRightId = R.id.ibHistory
        } else {
            ibVoiceSearch.setOnClickListener { initiateVoiceSearch() }
        }

        ibAdBlock.setOnClickListener { toggleAdBlockForTab() }
        ibHome.setOnClickListener { navigate(HOME_URL) }
        ibBack.setOnClickListener { navigateBack() }
        ibForward.setOnClickListener {
            if (viewModel.currentTab.value != null && (viewModel.currentTab.value!!.webView?.canGoForward() == true)) {
                viewModel.currentTab.value!!.webView?.goForward()
            }
        }
        ibRefresh.setOnClickListener { refresh() }
        ibCloseTab.setOnClickListener { viewModel.currentTab.value?.apply { closeTab(this) } }

        ibMenu.setOnClickListener { finish() }
        ibDownloads.setOnClickListener { startActivity(Intent(this@MainActivity, DownloadsActivity::class.java)) }
        ibFavorites.setOnClickListener { showFavorites() }
        ibHistory.setOnClickListener { showHistory() }
        ibSettings.setOnClickListener { showSettings() }
        ibZoomIn.setOnClickListener {
            viewModel.currentTab.value?.webView?.apply {
                if (this.canZoomIn()) this.zoomIn()
                onWebViewUpdated(viewModel.currentTab.value!!)
                if (!this.canZoomIn()) {
                    ibZoomOut.requestFocus()
                }
            }
        }
        ibZoomOut.setOnClickListener {
            viewModel.currentTab.value?.webView?.apply {
                if (this.canZoomOut()) this.zoomOut()
                onWebViewUpdated(viewModel.currentTab.value!!)
                if (!this.canZoomOut()) {
                    ibZoomIn.requestFocus()
                }
            }
        }

        etUrl.onFocusChangeListener = etUrlFocusChangeListener

        etUrl.setOnKeyListener(etUrlKeyListener)

        llBottomPanel.childs.forEach {
            it.setOnTouchListener(bottomButtonsOnTouchListener)
            it.onFocusChangeListener = bottomButtonsFocusListener
            it.setOnKeyListener(bottomButtonsKeyListener)
        }

        settingsViewModel.uaString.observe(this, Observer<String> { uas ->
            for (tab in viewModel.tabsStates) {
                tab.webView?.settings?.userAgentString = uas
                if (tab.webView != null && (uas == null || uas == "")) {
                    settingsViewModel.saveUAString(SettingsViewModel.TV_BRO_UA_PREFIX +
                            tab.webView!!.settings.userAgentString.replace("Mobile Safari", "Safari"))
                }
            }
        })

        loadState()
    }

    private val etUrlFocusChangeListener = View.OnFocusChangeListener { _, focused ->
        if (focused) {
            if (flUrl.parent == rlActionBar) {
                syncTabWithTitles()
                rlActionBar.removeView(flUrl)
                val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                rlRoot.addView(flUrl, lp)
                rlActionBar.visibility = View.INVISIBLE
                llBottomPanel.visibility = View.INVISIBLE
                ivMiniatures.visibility = View.INVISIBLE
                llMiniaturePlaceholder.visibility = View.INVISIBLE
                flWebViewContainer.visibility = View.VISIBLE
                TransitionManager.beginDelayedTransition(rlRoot)
            }

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etUrl, InputMethodManager.SHOW_FORCED)
            uiHandler.postDelayed(//workaround an android TV bug
                    {
                        etUrl.selectAll()
                    }, 500)
        }
    }

    private var progressBarHideRunnable: Runnable = Runnable {
        val anim = AnimationUtils.loadAnimation(this@MainActivity, android.R.anim.fade_out)
        anim.setAnimationListener(object : BaseAnimationListener() {
            override fun onAnimationEnd(animation: Animation) {
                progressBar.visibility = View.GONE
            }
        })
        progressBar.startAnimation(anim)
    }

    private var mConnectivityChangeReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetworkInfo
            val isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting
            if (viewModel.currentTab.value != null) {
                viewModel.currentTab.value!!.webView?.setNetworkAvailable(isConnected)
            }
        }
    }

    @ExperimentalStdlibApi
    private val tabsListener = object : TitlesView.Listener {
        override fun onTitleChanged(index: Int) {
            val tab = tabByTitleIndex(index)
            etUrl.setText(tab?.url ?: "")
            displayThumbnail(tab)
        }

        override fun onTitleSelected(index: Int) {
            syncTabWithTitles()
            hideMenuOverlay()
        }

        override fun onTitleOptions(index: Int) {
            val tab = tabByTitleIndex(index)
            showTabOptions(tab, index)
        }
    }

    private fun showHistory() {
        startActivityForResult(
                Intent(this@MainActivity, HistoryActivity::class.java),
                REQUEST_CODE_HISTORY_ACTIVITY)
        hideMenuOverlay()
    }

    private fun showFavorites() {
        val currentPageTitle = if (viewModel.currentTab.value != null) viewModel.currentTab.value!!.title else ""
        val currentPageUrl = if (viewModel.currentTab.value != null) viewModel.currentTab.value!!.url else ""

        FavoritesDialog(this@MainActivity, lifecycleScope, object : FavoritesDialog.Callback {
            override fun onFavoriteChoosen(item: FavoriteItem?) {
                navigate(item!!.url!!)
            }
        }, currentPageTitle, currentPageUrl).show()
        hideMenuOverlay()
    }

    private var downloadsServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as DownloadService.Binder
            downloadsService = binder.service
            downloadsService!!.registerListener(downloadsServiceListener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            downloadsService!!.unregisterListener(downloadsServiceListener)
            downloadsService = null
        }
    }

    private var downloadsServiceListener: DownloadService.Listener = object : DownloadService.Listener {
        override fun onDownloadUpdated(downloadInfo: Download) {

        }

        override fun onDownloadError(downloadInfo: Download, responseCode: Int, responseMessage: String) {

        }

        override fun onAllDownloadsComplete() {
            downloadAnimation?.apply {
                this.reset()
                ibDownloads.clearAnimation()
                downloadAnimation = null
            }
        }
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
                    viewModel.currentTab.value?.webView?.requestFocus()
                    flWebViewContainer.cursorPosition
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
                    imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
                    search(etUrl.text.toString())
                    hideFloatAddressBar()
                    viewModel.currentTab.value!!.webView?.requestFocus()
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
        if (flUrl.parent == rlRoot) {
            viewModel.currentTab.value?.apply { etUrl.setText(this.url) }
            rlRoot.removeView(flUrl)
            val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.addRule(RelativeLayout.END_OF, R.id.ibSettings)
            rlActionBar.addView(flUrl, lp)
            TransitionManager.beginDelayedTransition(rlRoot)
        }
    }

    @ExperimentalStdlibApi
    private fun showTabOptions(tab: WebTabState?, tabIndex: Int) {
        AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.tabs)
                .setItems(R.array.tabs_options) { _, i ->
                    when (i) {
                        0 -> tab?.apply { closeTab(this) }
                        1 -> {
                            viewModel.onCloseAllTabs()
                            flWebViewContainer.removeAllViews()
                            openInNewTab(HOME_URL, 0)
                            vTitles.titles = viewModel.tabsStates.map { it.title }.run { ArrayList(this) }
                            vTitles.current = viewModel.tabsStates.indexOf(viewModel.currentTab.value)
                        }
                        2 -> if (tab != null && tabIndex > 0) {
                            viewModel.tabsStates.remove(tab)
                            viewModel.tabsStates.add(tabIndex - 1, tab)
                            vTitles.titles = viewModel.tabsStates.map { it.title }.run { ArrayList(this) }
                            vTitles.current = tabIndex - 1
                        }
                        3 -> if (tab != null && tabIndex < (viewModel.tabsStates.size - 1)) {
                            viewModel.tabsStates.remove(tab)
                            viewModel.tabsStates.add(tabIndex + 1, tab)
                            vTitles.titles = viewModel.tabsStates.map { it.title }.run { ArrayList(this) }
                            vTitles.current = tabIndex + 1
                        }
                    }
                }
                .show()
    }

    private fun tabByTitleIndex(index: Int) =
            if (index >= 0 && index < viewModel.tabsStates.size) viewModel.tabsStates[index] else null

    fun showSettings() {
        SettingsDialog(this, settingsViewModel).show()
    }

    fun navigateBack(goHomeIfNoHistory: Boolean = false) {
        if (viewModel.currentTab.value != null && viewModel.currentTab.value!!.webView?.canGoBack() == true) {
            viewModel.currentTab.value!!.webView?.goBack()
        } else if (goHomeIfNoHistory) {
            navigate(HOME_URL)
        } else if (rlActionBar.visibility != View.VISIBLE) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    fun refresh() {
        viewModel.currentTab.value?.webView?.reload()
    }

    override fun onDestroy() {
        viewModel.jsInterface.setActivity(null)
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
        progressBarGeneric.visibility = View.VISIBLE
        progressBarGeneric.requestFocus()
        viewModel.loadState().join()

        if (!running) {
            return@launch
        }

        progressBarGeneric.visibility = View.GONE

        val intentUri = intent.data
        if (intentUri == null) {
            if (viewModel.tabsStates.isEmpty()) {
                openInNewTab(HOME_URL)
            } else {
                for (i in viewModel.tabsStates.indices) {
                    val tab = viewModel.tabsStates[i]
                    if (tab.selected) {
                        changeTab(tab)
                        break
                    }
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
            if (viewModel.currentTab.value == null ||
                    viewModel.currentTab.value!!.url == HOME_URL) {
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
            viewModel.logCatOutput().observe(this@MainActivity, Observer{ logMessage ->
                if (logMessage.endsWith("AwContentsClientBridge: Dropping new download request.")) {
                    viewModel.currentTab.value?.apply {
                        val url = this.lastLoadingUrl ?: return@apply
                        onDownloadRequested(url, this)
                    }
                }
            })
        }
    }

    private fun openInNewTab(url: String?, index: Int = 0) {
        if (url == null) {
            return
        }
        val tab = WebTabState()
        tab.url = url
        createWebView(tab) ?: return
        viewModel.tabsStates.add(index, tab)
        changeTab(tab)
        navigate(url)
        if (rlActionBar.visibility == View.VISIBLE) {
            hideMenuOverlay(true)
        }
    }

    private fun closeTab(tab: WebTabState?) {
        if (tab == null) return
        val position = viewModel.tabsStates.indexOf(tab)
        viewModel.currentTab.value = null
        tab.webView?.apply { flWebViewContainer.removeView(this) }
        when {
            viewModel.tabsStates.size == 1 -> openInNewTab(HOME_URL, 0)

            position == viewModel.tabsStates.size - 1 -> changeTab(viewModel.tabsStates[position - 1])

            else -> changeTab(viewModel.tabsStates[position + 1])
        }
        viewModel.onCloseTab(tab)
        vTitles.titles = viewModel.tabsStates.map { it.title }.run { ArrayList(this) }
        vTitles.current = viewModel.tabsStates.indexOf(viewModel.currentTab.value)
        hideBottomPanel()
    }

    private fun changeTab(newTab: WebTabState) {
        viewModel.tabsStates.forEach {
            it.selected = false
        }
        viewModel.currentTab.value?.apply {
            webView?.apply {
                onPause()
                flWebViewContainer.removeView(this)
            }
            onPause()
            viewModel.saveTab(this)
        }

        newTab.selected = true
        viewModel.currentTab.value = newTab
        vTitles.current = viewModel.tabsStates.indexOf(newTab)
        var wv = newTab.webView
        if (wv == null) {
            wv = createWebView(newTab)
            if (wv == null) {
                return
            }
            newTab.restoreWebView()
            flWebViewContainer.addView(newTab.webView)
        } else {
            (wv.parent as? ViewGroup)?.removeView(wv)
            flWebViewContainer.addView(wv)
            wv.onResume()
        }
        wv.setNetworkAvailable(Utils.isNetworkConnected(this))

        etUrl.setText(newTab.url)
        onWebViewUpdated(newTab)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: WebTabState): WebViewEx? {
        val webView: WebViewEx
        try {
            webView = WebViewEx(this)
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

        webView.addJavascriptInterface(viewModel.jsInterface, "TVBro")

        webView.setListener(object : WebViewEx.Callback {
            override fun getActivity(): Activity {
                return this@MainActivity
            }

            override fun onOpenInNewTabRequested(s: String) {
                var index = viewModel.tabsStates.indexOf(viewModel.currentTab.value)
                index = if (index == -1) viewModel.tabsStates.size else index + 1
                openInNewTab(s, index)
            }

            override fun onDownloadRequested(url: String) {
                onDownloadRequested(url, tab)
            }

            override fun onLongTap() {
                flWebViewContainer?.goToFingerMode()
            }

            override fun onThumbnailError() {
                //nop for now
            }

            override fun onShowCustomView(view: View) {
                tab.webView?.visibility = View.GONE
                flFullscreenContainer.visibility = View.VISIBLE
                flFullscreenContainer.addView(view)
                flFullscreenContainer.cursorPosition.set(flWebViewContainer.cursorPosition)
                fullScreenView = view
            }

            override fun onHideCustomView() {
                if (fullScreenView != null) {
                    flFullscreenContainer.removeView(fullScreenView)
                    fullScreenView = null
                }
                flWebViewContainer.cursorPosition.set(flFullscreenContainer.cursorPosition)
                flFullscreenContainer.visibility = View.INVISIBLE
                tab.webView?.visibility = View.VISIBLE
            }

            override fun onProgressChanged(newProgress: Int) {
                progressBar.visibility = View.VISIBLE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    progressBar.setProgress(newProgress, true)
                } else {
                    progressBar.progress = newProgress
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
                vTitles.titles = viewModel.tabsStates.map { it.title }.run { ArrayList(this) }
                vTitles.postInvalidate()
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
                    Utils.showToast(applicationContext, getString(R.string.err_cant_open_file_chooser))
                    return false
                }
                return true
            }

            override fun onReceivedIcon(icon: Bitmap) {
                tab.updateFavIcon(this@MainActivity, icon)
            }

            override fun shouldOverrideUrlLoading(url: String): Boolean {
                tab.lastLoadingUrl = url

                if (URLUtil.isNetworkUrl(url)) {
                    tab.url = url
                    if (tabByTitleIndex(vTitles.current) == tab) {
                        etUrl.setText(tab.url)
                    }
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
                if (tabByTitleIndex(vTitles.current) == tab) {
                    etUrl.setText(tab.url)
                }
            }

            override fun onPageFinished(url: String?) {
                if (tab.webView == null || viewModel.currentTab.value == null) {
                    return
                }
                onWebViewUpdated(tab)

                val webViewUrl = tab.webView?.url
                if (webViewUrl != null) {
                    tab.url = webViewUrl
                } else if (url != null) {
                    tab.url = url
                }
                if (tabByTitleIndex(vTitles.current) == tab) {
                    etUrl.setText(tab.url)
                }

                //thumbnail
                viewModel.tabsStates.onEach { if (it != tab) it.thumbnail = null }
                val newThumbnail = tab.webView?.renderThumbnail(tab.thumbnail)
                if (newThumbnail != null) {
                    tab.updateThumbnail(this@MainActivity, newThumbnail, lifecycleScope)
                    if (rlActionBar.visibility == View.VISIBLE && tab == viewModel.currentTab.value) {
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
                etUrl.setTextColor(Color.RED)
            }

            override fun isAd(url: Uri): Boolean {
                return adblockViewModel.isAd(url)
            }
        })

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.i(TAG, "DownloadListener.onDownloadStart url: $url")
            onDownloadRequested(url, tab.url ?: "", DownloadUtils.guessFileName(url, contentDisposition, mimetype), userAgent
                    ?: tab.webView?.settings?.userAgentString ?: getString(R.string.app_name), mimetype)
        }

        webView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && flUrl.parent == rlRoot) {
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
        ibBack.isEnabled = tab.webView?.canGoBack() == true
        ibForward.isEnabled = tab.webView?.canGoForward() == true
        val zoomPossible = tab.webView?.canZoomIn() == true || tab.webView?.canZoomOut() == true
        ibZoomIn.visibility = if (zoomPossible) View.VISIBLE else View.GONE
        ibZoomOut.visibility = if (zoomPossible) View.VISIBLE else View.GONE
        ibZoomIn.isEnabled = tab.webView?.canZoomIn() == true
        ibZoomOut.isEnabled = tab.webView?.canZoomOut() == true
    }

    private fun onDownloadRequested(url: String, referer: String, originalDownloadFileName: String, userAgent: String, mimeType: String?,
                                    operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP) {
        viewModel.onDownloadRequested(this, url, referer, originalDownloadFileName, userAgent, mimeType, operationAfterDownload)
    }

    override fun onTrimMemory(level: Int) {
        for (tab in viewModel.tabsStates) {
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
                viewModel.currentTab.value?.webView?.onPermissionsResult(permissions, grantResults, false)
                return
            }
            MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS -> {
                viewModel.currentTab.value?.webView?.onPermissionsResult(permissions, grantResults, true)
                return
            }
            MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startDownload(this)
                }
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
                    viewModel.currentTab.value?.webView?.onFilePicked(data)
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
        viewModel.jsInterface.setActivity(this)
        val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        registerReceiver(mConnectivityChangeReceiver, intentFilter)
        if (viewModel.currentTab.value != null) {
            viewModel.currentTab.value!!.webView?.onResume()
        }
        vTitles.titles = viewModel.tabsStates.map { it.title }.run { ArrayList(this) }
        vTitles.current = viewModel.tabsStates.indexOf(viewModel.currentTab.value)
        bindService(Intent(this, DownloadService::class.java), downloadsServiceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        viewModel.jsInterface.setActivity(null)
        unbindService(downloadsServiceConnection)
        if (mConnectivityChangeReceiver != null) unregisterReceiver(mConnectivityChangeReceiver)
        viewModel.currentTab.value?.apply {
            webView?.onPause()
            onPause()
            viewModel.saveTab(this, true)
        }

        super.onPause()
        running = false
    }

    private fun toggleAdBlockForTab() {

    }

    fun navigate(url: String) {
        etUrl.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.default_url_color))
        if (viewModel.currentTab.value != null) {
            viewModel.currentTab.value!!.webView?.loadUrl(url)
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
        if (rlActionBar.visibility == View.INVISIBLE) {
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
                    viewModel.currentTab.value?.webView?.onHideCustomView()
                }
            }
            return true
        } else if (keyCode == KeyEvent.KEYCODE_BACK && flUrl.parent == rlRoot) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { hideFloatAddressBar() }
            }
            return true
        } else if (keyCode == KeyEvent.KEYCODE_BACK && llBottomPanel.visibility == View.VISIBLE && rlActionBar.visibility != View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { hideBottomPanel() }
            }
            return true
        } else if (keyCode == KeyEvent.KEYCODE_BACK && flWebViewContainer.fingerMode) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { flWebViewContainer.exitFingerMode() }
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
        ivMiniatures.visibility = View.VISIBLE
        llBottomPanel.visibility = View.VISIBLE
        flWebViewContainer.visibility = View.INVISIBLE
        val currentTab = viewModel.currentTab.value
        if (currentTab != null) {
            currentTab.thumbnail = currentTab.webView?.renderThumbnail(currentTab.thumbnail)
            displayThumbnail(currentTab)
        }

        llBottomPanel.translationY = llBottomPanel.height.toFloat()
        llBottomPanel.alpha = 0f
        llBottomPanel.animate()
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .translationY(0f)
                .alpha(1f)
                .withEndAction {
                    ibMenu.requestFocus()
                }
                .start()

        rlActionBar.visibility = View.VISIBLE
        rlActionBar.translationY = -rlActionBar.height.toFloat()
        rlActionBar.alpha = 0f
        rlActionBar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()

        ivMiniatures.layoutParams = ivMiniatures.layoutParams.apply { this.height = flWebViewContainer.height }
        ivMiniatures.translationY = 0f
        ivMiniatures.animate()
                .translationY(rlActionBar.height.toFloat())
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
            if (tabByTitleIndex(vTitles.current) != currentTab) return
            llMiniaturePlaceholder.visibility = View.INVISIBLE
            ivMiniatures.visibility = View.VISIBLE
            if (currentTab.thumbnail != null) {
                ivMiniatures.setImageBitmap(currentTab.thumbnail)
            } else if (currentTab.thumbnailHash != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val thumbnail = currentTab.loadThumbnail()
                    launch(Dispatchers.Main) {
                        if (thumbnail != null) {
                            ivMiniatures.setImageBitmap(currentTab.thumbnail)
                        } else {
                            ivMiniatures.setImageResource(0)
                        }
                    }
                }
            } else {
                ivMiniatures.setImageResource(0)
            }
        } else {
            llMiniaturePlaceholder.visibility = View.VISIBLE
            ivMiniatures.setImageResource(0)
            ivMiniatures.visibility = View.INVISIBLE
        }
    }

    private fun hideMenuOverlay(hideBottomButtons: Boolean = true) {
        if (rlActionBar.visibility == View.INVISIBLE) {
            return
        }
        if (hideBottomButtons) {
            hideBottomPanel()
        }

        rlActionBar.animate()
                .translationY(-rlActionBar.height.toFloat())
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    rlActionBar.visibility = View.INVISIBLE
                }
                .start()

        if (llMiniaturePlaceholder.visibility == View.VISIBLE) {
            llMiniaturePlaceholder.visibility = View.INVISIBLE
            ivMiniatures.visibility = View.VISIBLE
        }

        ivMiniatures.translationY = rlActionBar.height.toFloat()
        ivMiniatures.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    ivMiniatures.visibility = View.INVISIBLE
                    rlActionBar.visibility = View.INVISIBLE
                    ivMiniatures.setImageResource(0)
                    syncTabWithTitles()
                    flWebViewContainer.visibility = View.VISIBLE
                    if (hideBottomButtons && viewModel.currentTab.value != null && flUrl.parent != rlRoot) {
                        viewModel.currentTab.value!!.webView?.requestFocus()
                    }
                }
                .start()
    }

    private fun syncTabWithTitles() {
        val tab = tabByTitleIndex(vTitles.current)
        if (tab == null) {
            openInNewTab(HOME_URL, if (vTitles.current < 0) 0 else viewModel.tabsStates.size)
        } else if (!tab.selected) {
            changeTab(tab)
        }
    }

    private fun hideBottomPanel() {
        if (llBottomPanel.visibility != View.VISIBLE) return
        llBottomPanel.animate()
                .setDuration(300)
                .setInterpolator(AccelerateInterpolator())
                .translationY(llBottomPanel.height.toFloat())
                .withEndAction {
                    llBottomPanel.translationY = 0f
                    llBottomPanel.visibility = View.INVISIBLE
                }
                .start()
    }

    fun onDownloadStarted(fileName: String) {
        Utils.showToast(this, getString(R.string.download_started,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + File.separator + fileName))
        showMenuOverlay()
        if (downloadAnimation == null) {
            downloadAnimation = AnimationUtils.loadAnimation(this, R.anim.infinite_fadeinout_anim)
            ibDownloads.startAnimation(downloadAnimation)
        }
    }

    fun initiateVoiceSearch() {
        hideMenuOverlay()
        VoiceSearchHelper.initiateVoiceSearch(this, VOICE_SEARCH_REQUEST_CODE)
    }
}
