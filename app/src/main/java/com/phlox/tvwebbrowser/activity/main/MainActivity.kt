package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.net.http.SslError
import android.os.*
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.transition.TransitionManager
import android.util.Log
import android.util.Patterns
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity
import com.phlox.tvwebbrowser.activity.history.HistoryActivity
import com.phlox.tvwebbrowser.activity.main.dialogs.FavoritesDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.SearchEngineConfigDialogFactory
import com.phlox.tvwebbrowser.activity.main.dialogs.settings.SettingsDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.settings.SettingsViewModel
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


class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {
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
    private lateinit var uiHandler: Handler
    private var fullscreenViewCallback: WebChromeClient.CustomViewCallback? = null
    private var permRequestDialog: AlertDialog? = null
    private var webPermissionsRequest: PermissionRequest? = null
    private var reuestedResourcesForAlreadyGrantedPermissions: ArrayList<String>? = null
    private var geoPermissionOrigin: String? = null
    private var geoPermissionsCallback: GeolocationPermissions.Callback? = null
    private var running: Boolean = false
    private var pickFileCallback: ValueCallback<Array<Uri>>? = null
    private var downloadsService: DownloadService? = null
    private var downloadAnimation: Animation? = null
    private var fullScreenView: View? = null
    private lateinit var prefs: SharedPreferences

    @ExperimentalStdlibApi
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProviders.of(this).get(MainActivityViewModel::class.java)
        settingsViewModel = ViewModelProviders.of(this).get(SettingsViewModel::class.java)
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
                        viewModel.logVisitedHistory(tab.currentTitle, tab.currentOriginalUrl, tab.faviconHash)
                    }
                }
            }
        })

        ibVoiceSearch.setOnClickListener { initiateVoiceSearch() }

        ibHome.setOnClickListener {navigate(HOME_URL) }
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

        etUrl.onFocusChangeListener = etUrlFocusChangeListener

        etUrl.setOnKeyListener(etUrlKeyListener)

        ibForward.onFocusChangeListener = bottomButtonsFocusListener
        ibBack.onFocusChangeListener = bottomButtonsFocusListener
        ibRefresh.onFocusChangeListener = bottomButtonsFocusListener
        ibHome.onFocusChangeListener = bottomButtonsFocusListener
        ibCloseTab.onFocusChangeListener = bottomButtonsFocusListener
        ibForward.setOnKeyListener(bottomButtonsKeyListener)
        ibBack.setOnKeyListener(bottomButtonsKeyListener)
        ibRefresh.setOnKeyListener(bottomButtonsKeyListener)
        ibHome.setOnKeyListener(bottomButtonsKeyListener)
        ibCloseTab.setOnKeyListener(bottomButtonsKeyListener)

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
            etUrl.setText(tab?.currentOriginalUrl ?: "")
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
        val currentPageTitle = if (viewModel.currentTab.value != null) viewModel.currentTab.value!!.currentTitle else ""
        val currentPageUrl = if (viewModel.currentTab.value != null) viewModel.currentTab.value!!.currentOriginalUrl else ""

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
            if (downloadAnimation != null) {
                downloadAnimation!!.reset()
                ibDownloads.clearAnimation()
                downloadAnimation = null
            }
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
                    hideFloatAddressBar()
                    search(etUrl.text.toString())
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
            viewModel.currentTab.value?.apply { etUrl.setText(this.currentOriginalUrl) }
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
                            viewModel.tabsStates.forEach { it.removeFiles() }
                            openInNewTab(HOME_URL, 0)
                            while (viewModel.tabsStates.size > 1) viewModel.tabsStates.removeLast()
                            vTitles.titles = viewModel.tabsStates.map { it.currentTitle }.run { ArrayList(this) }
                            vTitles.current = viewModel.tabsStates.indexOf(viewModel.currentTab.value)
                        }
                        2 -> if (tab != null && tabIndex > 0) {
                            viewModel.tabsStates.remove(tab)
                            viewModel.tabsStates.add(tabIndex - 1, tab)
                            vTitles.titles = viewModel.tabsStates.map { it.currentTitle }.run { ArrayList(this) }
                            vTitles.current = tabIndex - 1
                        }
                        3 -> if (tab != null && tabIndex < (viewModel.tabsStates.size - 1)) {
                            viewModel.tabsStates.remove(tab)
                            viewModel.tabsStates.add(tabIndex + 1, tab)
                            vTitles.titles = viewModel.tabsStates.map { it.currentTitle }.run { ArrayList(this) }
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

    private fun loadState() = launch(Dispatchers.Main) {
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
                openInNewTab(WebViewEx.HOME_URL)
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
                    viewModel.currentTab.value!!.currentOriginalUrl == WebViewEx.HOME_URL) {
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
    }

    private fun openInNewTab(url: String?, index: Int = 0) {
        if (url == null) {
            return
        }
        val tab = WebTabState()
        tab.currentOriginalUrl = url
        if (!createWebView(tab)) {
            return
        }
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
        when {
            viewModel.tabsStates.size == 1 -> openInNewTab(HOME_URL, 0)

            position == viewModel.tabsStates.size - 1 -> changeTab(viewModel.tabsStates[position - 1])

            else -> changeTab(viewModel.tabsStates[position + 1])
        }
        viewModel.tabsStates.remove(tab)
        vTitles.titles = viewModel.tabsStates.map { it.currentTitle }.run { ArrayList(this) }
        vTitles.current = viewModel.tabsStates.indexOf(viewModel.currentTab.value)
        tab.removeFiles()
        hideBottomPanel()
    }

    private fun changeTab(newTab: WebTabState) {
        viewModel.tabsStates.forEach {
            it.selected = false
        }
        viewModel.currentTab.value?.webView?.apply {
            onPause()
            flWebViewContainer.removeView(this)
        }

        newTab.selected = true
        viewModel.currentTab.value = newTab
        vTitles.current = viewModel.tabsStates.indexOf(newTab)
        if (newTab.webView == null) {
            if (!createWebView(newTab)) {
                return
            }
            newTab.restoreWebView()
            flWebViewContainer.addView(newTab.webView)
        } else {
            flWebViewContainer.addView(newTab.webView)
            newTab.webView!!.onResume()
        }
        newTab.webView!!.setNetworkAvailable(Utils.isNetworkConnected(this))

        etUrl.setText(newTab.currentOriginalUrl)
        ibBack.isEnabled = newTab.webView?.canGoBack() == true
        ibForward.isEnabled = newTab.webView?.canGoForward() == true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: WebTabState): Boolean {
        val webView: WebViewEx
        try {
            webView = WebViewEx(this)
            tab.webView = webView
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
            Toast.makeText(this,
                    getString(R.string.err_webview_can_not_link),
                    Toast.LENGTH_LONG).show()
            finish()
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this,
                    getString(R.string.err_webview_can_not_link),
                    Toast.LENGTH_LONG).show()
            finish()
            return false
        }

        if (settingsViewModel.uaString.value == null || settingsViewModel.uaString.value == "") {
            settingsViewModel.saveUAString("TV Bro/1.0 " + webView.settings.userAgentString.replace("Mobile Safari", "Safari"))
        }
        webView.settings.userAgentString = settingsViewModel.uaString.value

        webView.addJavascriptInterface(viewModel.jsInterface, "TVBro")

        webView.setListener(object : WebViewEx.Listener {
            override fun onOpenInNewTabRequested(s: String) {
                openInNewTab(s)
            }

            override fun onDownloadRequested(url: String) {
                val fileName = Uri.parse(url).lastPathSegment
                onDownloadRequested(url, fileName
                        ?: "url.html", tab.webView?.settings?.userAgentString)
            }

            override fun onWantZoomMode() {
                flWebViewContainer?.goToZoomMode()
            }

            override fun onThumbnailError() {
                //nop for now
            }
        })

        tab.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return super.onJsAlert(view, url, message, result)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                tab.webView?.visibility = View.GONE
                flFullscreenContainer.visibility = View.VISIBLE
                flFullscreenContainer.addView(view)
                flFullscreenContainer.cursorPosition.set(flWebViewContainer.cursorPosition)

                fullScreenView = view
                fullscreenViewCallback = callback
            }

            override fun onHideCustomView() {
                if (fullScreenView != null) {
                    flFullscreenContainer.removeView(fullScreenView)
                    fullScreenView = null
                }

                fullscreenViewCallback?.onCustomViewHidden()

                flWebViewContainer.cursorPosition.set(flFullscreenContainer.cursorPosition)
                flFullscreenContainer.visibility = View.INVISIBLE
                tab.webView?.visibility = View.VISIBLE
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
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

            override fun onReceivedTitle(view: WebView, title: String) {
                super.onReceivedTitle(view, title)
                tab.currentTitle = title
                vTitles.titles = viewModel.tabsStates.map { it.currentTitle }.run { ArrayList(this) }
                vTitles.postInvalidate()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                webPermissionsRequest = request
                permRequestDialog = AlertDialog.Builder(this@MainActivity)
                        .setMessage(getString(R.string.web_perm_request_confirmation, TextUtils.join("\n", request.resources)))
                        .setCancelable(false)
                        .setNegativeButton(R.string.deny) { dialog, which ->
                            webPermissionsRequest!!.deny()
                            permRequestDialog = null
                            webPermissionsRequest = null
                        }
                        .setPositiveButton(R.string.allow) { dialog, which ->
                            val webPermissionsRequest = this@MainActivity.webPermissionsRequest
                            this@MainActivity.webPermissionsRequest = null
                            if (webPermissionsRequest == null) {
                                return@setPositiveButton
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val neededPermissions = ArrayList<String>()
                                reuestedResourcesForAlreadyGrantedPermissions = ArrayList()
                                for (resource in webPermissionsRequest.resources) {
                                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE == resource) {
                                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
                                        } else {
                                            reuestedResourcesForAlreadyGrantedPermissions!!.add(resource)
                                        }
                                    } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE == resource) {
                                        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                            neededPermissions.add(Manifest.permission.CAMERA)
                                        } else {
                                            reuestedResourcesForAlreadyGrantedPermissions!!.add(resource)
                                        }
                                    }
                                }

                                if (!neededPermissions.isEmpty()) {
                                    requestPermissions(neededPermissions.toTypedArray(),
                                            MY_PERMISSIONS_REQUEST_WEB_PAGE_PERMISSIONS)
                                } else {
                                    if (reuestedResourcesForAlreadyGrantedPermissions!!.isEmpty()) {
                                        webPermissionsRequest.deny()
                                    } else {
                                        webPermissionsRequest.grant(reuestedResourcesForAlreadyGrantedPermissions!!.toTypedArray())
                                    }
                                }
                            } else {
                                webPermissionsRequest.grant(webPermissionsRequest.resources)
                            }
                            permRequestDialog = null
                        }
                        .create()
                permRequestDialog!!.show()
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (permRequestDialog != null) {
                    permRequestDialog!!.dismiss()
                    permRequestDialog = null
                }
                webPermissionsRequest = null
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                geoPermissionOrigin = origin
                geoPermissionsCallback = callback
                permRequestDialog = AlertDialog.Builder(this@MainActivity)
                        .setMessage(getString(R.string.web_perm_request_confirmation, getString(R.string.location)))
                        .setCancelable(false)
                        .setNegativeButton(R.string.deny) { dialog, which ->
                            geoPermissionsCallback!!.invoke(geoPermissionOrigin, false, false)
                            permRequestDialog = null
                            geoPermissionsCallback = null
                        }
                        .setPositiveButton(R.string.allow) { dialog, which ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                                        MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS)
                            } else {
                                geoPermissionsCallback!!.invoke(geoPermissionOrigin, true, true)
                                geoPermissionsCallback = null
                            }
                            permRequestDialog = null
                        }
                        .create()
                permRequestDialog!!.show()
            }

            override fun onGeolocationPermissionsHidePrompt() {
                if (permRequestDialog != null) {
                    permRequestDialog!!.dismiss()
                    permRequestDialog = null
                }
                geoPermissionsCallback = null
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.i("TV Bro (" + consoleMessage.sourceId() + "[" + consoleMessage.lineNumber() + "])", consoleMessage.message())
                return true
            }


            override fun onShowFileChooser(mWebView: WebView, callback: ValueCallback<Array<Uri>>, fileChooserParams: WebChromeClient.FileChooserParams): Boolean {
                pickFileCallback = callback
                try {
                    startActivityForResult(fileChooserParams.createIntent(), PICKFILE_REQUEST_CODE)
                } catch (e: ActivityNotFoundException) {
                    pickFileCallback = null
                    Utils.showToast(applicationContext, getString(R.string.err_cant_open_file_chooser))
                    return false
                }

                return true
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                tab.updateFavIcon(this@MainActivity, icon)
            }

            /*override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val tab = WebTabState()
                //tab.currentOriginalUrl = url;
                createWebView(tab)
                val currentTab = this@MainActivity.viewModel.currentTab.value
                val index = if (currentTab == null) 0 else viewModel.tabsStates.indexOf(currentTab)
                viewModel.tabsStates.add(index, tab)
                changeTab(tab)
                (resultMsg.obj as WebView.WebViewTransport).webView = tab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                for (tab in viewModel.tabsStates) {
                    if (tab.webView == window) {
                        closeTab(tab)
                        break
                    }
                }
            }*/
        }

        webView.webChromeClient = tab.webChromeClient

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url: String = request?.url.toString()

                if (URLUtil.isNetworkUrl(url)) {
                    return false
                }

                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)

                intent.putExtra("URL_INTENT_ORIGIN", view?.hashCode())
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null

                if (intent.resolveActivity(this@MainActivity.packageManager) != null) {
                    startActivityIfNeeded(intent, -1)

                    return true
                }

                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                ibBack.isEnabled = tab.webView?.canGoBack() == true
                ibForward.isEnabled = tab.webView?.canGoForward() == true
                if (tab.webView?.url != null) {
                    tab.currentOriginalUrl = tab.webView?.url
                } else if (url != null) {
                    tab.currentOriginalUrl = url
                }
                if (tabByTitleIndex(vTitles.current) == tab) {
                    etUrl.setText(tab.currentOriginalUrl)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (tab.webView == null || viewModel.currentTab.value == null || view == null) {
                    return
                }
                ibBack!!.isEnabled = tab.webView?.canGoBack() == true
                ibForward!!.isEnabled = tab.webView?.canGoForward() == true

                if (tab.webView?.url != null) {
                    tab.currentOriginalUrl = tab.webView?.url
                } else if (url != null) {
                    tab.currentOriginalUrl = url
                }
                if (tabByTitleIndex(vTitles.current) == tab) {
                    etUrl.setText(tab.currentOriginalUrl)
                }

                //thumbnail
                viewModel.tabsStates.onEach { if (it != tab) it.thumbnail = null }
                val newThumbnail = tab.webView?.renderThumbnail(tab.thumbnail)
                if (newThumbnail != null) {
                    tab.updateThumbnail(this@MainActivity, newThumbnail, this@MainActivity)
                    if (rlActionBar.visibility == View.VISIBLE && tab == viewModel.currentTab.value) {
                        displayThumbnail(tab)
                    }
                }

                tab.webView?.evaluateJavascript(Scripts.INITIAL_SCRIPT, null)
                tab.webPageInteractionDetected = false
                if (HOME_URL == url) {
                    view.loadUrl("javascript:renderSuggestions()")
                }
            }

            override fun onLoadResource(view: WebView, url: String) {
                super.onLoadResource(view, url)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                if (tab.trustSsl && tab.lastSSLError?.certificate?.toString()?.equals(error.certificate.toString()) == true) {
                    tab.trustSsl = false
                    tab.lastSSLError = null
                    handler.proceed()
                } else {
                    handler.cancel()
                    showCertificateErrorPage(error)
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            onDownloadRequested(url, DownloadUtils.guessFileName(url, contentDisposition, mimetype), userAgent
                    ?: tab.webView?.settings?.userAgentString)
        }

        webView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && flUrl.parent == rlRoot) {
                hideFloatAddressBar()
            }
        }

        return true
    }

    private fun showCertificateErrorPage(error: SslError) {
        val tab = viewModel.currentTab.value ?: return
        val webView = tab.webView ?: return
        etUrl.setTextColor(Color.RED)
        tab.lastSSLError = error
        val url = WebViewEx.INTERNAL_SCHEME + WebViewEx.INTERNAL_SCHEME_WARNING_DOMAIN +
                "?type=" + WebViewEx.INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT +
                "&url=" + URLEncoder.encode(error.url, "UTF-8")
        webView.loadUrl(url)
    }

    fun onDownloadRequested(url: String, originalDownloadFileName: String?, userAgent: String?,
                            operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP) {
        viewModel.onDownloadRequested(this, url, originalDownloadFileName, userAgent, operationAfterDownload)
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
                if (webPermissionsRequest == null) {
                    return
                }
                // If request is cancelled, the result arrays are empty.
                val resources = ArrayList<String>()
                for (i in permissions.indices) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        if (Manifest.permission.CAMERA == permissions[i]) {
                            resources.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        } else if (Manifest.permission.RECORD_AUDIO == permissions[i]) {
                            resources.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        }
                    }
                }
                resources.addAll(reuestedResourcesForAlreadyGrantedPermissions!!)
                if (resources.isEmpty()) {
                    webPermissionsRequest!!.deny()
                } else {
                    webPermissionsRequest!!.grant(resources.toTypedArray())
                }
                webPermissionsRequest = null
                return
            }
            MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS -> {
                if (geoPermissionsCallback == null) {
                    return
                }
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    geoPermissionsCallback!!.invoke(geoPermissionOrigin, true, true)
                } else {
                    geoPermissionsCallback!!.invoke(geoPermissionOrigin, false, false)
                }
                geoPermissionsCallback = null
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
                if (resultCode == Activity.RESULT_OK && pickFileCallback != null &&
                        data != null && data.data != null) {
                    val uris = arrayOf(data.data)
                    pickFileCallback!!.onReceiveValue(uris)
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
        bindService(Intent(this, DownloadService::class.java), downloadsServiceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        viewModel.jsInterface.setActivity(null)
        unbindService(downloadsServiceConnection)
        if (viewModel.currentTab.value != null) {
            viewModel.currentTab.value!!.webView?.onPause()
        }
        if (mConnectivityChangeReceiver != null) unregisterReceiver(mConnectivityChangeReceiver)
        launch(Dispatchers.Main) { viewModel.saveState() }
        super.onPause()
        running = false
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
        var text = text
        val trimmedLowercased = text.trim { it <= ' ' }.toLowerCase()
        if (Patterns.WEB_URL.matcher(text).matches() || trimmedLowercased.startsWith("http://") || trimmedLowercased.startsWith("https://")) {
            if (!text.toLowerCase().contains("://")) {
                text = "http://$text"
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
                    viewModel.currentTab.value?.webChromeClient?.onHideCustomView()
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
        } else if (keyCode == KeyEvent.KEYCODE_BACK && flWebViewContainer.zoomMode) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { flWebViewContainer.exitZoomMode() }
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
                launch(Dispatchers.IO) {
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
        viewModel.initiateVoiceSearch(this)
    }
}
