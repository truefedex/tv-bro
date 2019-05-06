package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.net.http.SslError
import android.os.*
import android.speech.RecognizerIntent
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import android.util.Log
import android.util.Patterns
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity
import com.phlox.tvwebbrowser.activity.history.HistoryActivity
import com.phlox.tvwebbrowser.activity.main.adapter.TabsListAdapter
import com.phlox.tvwebbrowser.activity.main.dialogs.FavoritesDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.SearchEngineConfigDialogFactory
import com.phlox.tvwebbrowser.activity.main.dialogs.settings.SettingsDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.settings.SettingsViewModel
import com.phlox.tvwebbrowser.activity.main.view.CursorLayout
import com.phlox.tvwebbrowser.activity.main.view.Scripts
import com.phlox.tvwebbrowser.activity.main.view.WebTabItemView
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.model.*
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
    private var tabsAdapter: TabsListAdapter? = null
    private var thumbnailesSize: Size? = null
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
    private val webViews = ArrayList<WebViewEx>()

    internal var progressBarHideRunnable: Runnable = Runnable {
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

    internal var tabsEventsListener: WebTabItemView.Listener = object : WebTabItemView.Listener {
        override fun onTabSelected(tab: WebTabState) {
            if (!tab.selected) {
                changeTab(tab)
            }
        }

        override fun onTabDeleteClicked(tab: WebTabState) {
            closeTab(tab)
        }

        override fun onNeededThumbnailSizeCalculated(width: Int, height: Int) {
            if (thumbnailesSize == null) {
                thumbnailesSize = Size(width, height)
            } else if (thumbnailesSize!!.width == width && thumbnailesSize!!.height == height) {
                return
            }
            if (viewModel.currentTab.value != null) {
                viewModel.currentTab.value!!.webView?.setNeedThumbnail(thumbnailesSize)
                viewModel.currentTab.value!!.webView?.postInvalidate()
            }
        }
    }

    fun showHistory() {
        startActivityForResult(
                Intent(this@MainActivity, HistoryActivity::class.java),
                REQUEST_CODE_HISTORY_ACTIVITY)
        hideMenuOverlay()
    }

    fun showFavorites() {
        val currentPageTitle = if (viewModel.currentTab.value != null) viewModel.currentTab.value!!.currentTitle else ""
        val currentPageUrl = if (viewModel.currentTab.value != null) viewModel.currentTab.value!!.currentOriginalUrl else ""
        FavoritesDialog(this@MainActivity, object : FavoritesDialog.Callback {
            override fun onFavoriteChoosen(item: FavoriteItem?) {
                navigate(item!!.url!!)
            }
        }, currentPageTitle!!, currentPageUrl!!).show()
        hideMenuOverlay()
    }

    internal var downloadsServiceConnection: ServiceConnection = object : ServiceConnection {
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

    internal var downloadsServiceListener: DownloadService.Listener = object : DownloadService.Listener {
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

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProviders.of(this).get(MainActivityViewModel::class.java)
        settingsViewModel = ViewModelProviders.of(this).get(SettingsViewModel::class.java)
        uiHandler = Handler()
        prefs = getSharedPreferences(TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        setContentView(R.layout.activity_main)
        AndroidBug5497Workaround.assistActivity(this)

        llMenuOverlay.visibility = View.GONE
        llActionBar.visibility = View.GONE
        progressBar.visibility = View.GONE

        tabsAdapter = TabsListAdapter(viewModel.tabsStates, tabsEventsListener)
        lvTabs.adapter = tabsAdapter
        lvTabs.itemsCanFocus = true

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

        btnNewTab.setOnClickListener { openInNewTab(WebViewEx.HOME_URL) }

        ibVoiceSearch.setOnClickListener { viewModel.initiateVoiceSearch(this) }

        /*ibHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                navigate(HOME_URL);
            }
        });*/
        ibBack.setOnClickListener { navigateBack() }
        ibForward.setOnClickListener {
            if (viewModel.currentTab.value != null && (viewModel.currentTab.value!!.webView?.canGoForward() == true)) {
                viewModel.currentTab.value!!.webView?.goForward()
            }
        }
        ibRefresh.setOnClickListener { refresh() }

        ibMenu.setOnClickListener { finish() }

        ibDownloads.setOnClickListener { startActivity(Intent(this@MainActivity, DownloadsActivity::class.java)) }

        ibFavorites.setOnClickListener { showFavorites() }

        ibHistory.setOnClickListener { showHistory() }

        ibSettings.setOnClickListener {
            showSettings()
        }

        flMenuRightContainer.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            if (focused && llMenuOverlay.visibility == View.VISIBLE) {
                hideMenuOverlay()
            }
        }

        etUrl.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            if (focused) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etUrl, InputMethodManager.SHOW_FORCED)
                uiHandler!!.postDelayed(//workaround an android TV bug
                        {
                            etUrl.selectAll()
                        }, 500)
            }
        }

        etUrl.setOnKeyListener(View.OnKeyListener { view, i, keyEvent ->
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_ENTER -> {
                    if (keyEvent.action == KeyEvent.ACTION_UP) {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(etUrl!!.windowToken, 0)
                        hideMenuOverlay()
                        search(etUrl.text.toString())
                        viewModel.currentTab.value!!.webView?.requestFocus()
                    }
                    return@OnKeyListener true
                }
            }
            false
        })


        settingsViewModel.uaString.observe(this, object : Observer<String> {
            override fun onChanged(uas: String?) {
                for (tab in viewModel.tabsStates) {
                    tab.webView?.settings?.userAgentString = uas
                    if (tab.webView != null && (uas == null || uas == "")) {
                        settingsViewModel.saveUAString(SettingsViewModel.TV_BRO_UA_PREFIX +
                                tab.webView!!.settings.userAgentString.replace("Mobile Safari", "Safari"))
                    }
                }
            }
        })

        loadState()
    }

    public fun showSettings() {
        SettingsDialog(this, settingsViewModel).show()
    }

    fun navigateBack() {
        if (viewModel.currentTab.value != null && viewModel.currentTab.value!!.webView?.canGoBack() == true) {
            viewModel.currentTab.value!!.webView?.goBack()
        }
    }

    fun refresh() {
        if (viewModel.currentTab.value != null) {
            viewModel.currentTab.value!!.webView?.reload()
        }
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
                                settingsViewModel.checkUpdate{
                                    settingsViewModel.showUpdateDialogIfNeeded(this@MainActivity)
                                }
                            }
                        }
                    })
        } else {
            if (settingsViewModel.needAutockeckUpdates &&
                    settingsViewModel.updateChecker.versionCheckResult == null &&
                    !settingsViewModel.lastUpdateNotificationTime.sameDay(Calendar.getInstance())) {
                settingsViewModel.checkUpdate{
                    settingsViewModel.showUpdateDialogIfNeeded(this@MainActivity)
                }
            }
        }
    }

    private fun openInNewTab(url: String?) {
        if (url == null) {
            return
        }
        val tab = WebTabState()
        tab.currentOriginalUrl = url
        createWebView(tab)
        viewModel.tabsStates.add(0, tab)
        changeTab(tab)
        navigate(url)
    }

    private fun closeTab(tab: WebTabState?) {
        if (tab == null) return
        val position = viewModel.tabsStates.indexOf(tab)
        when {
            viewModel.tabsStates.size == 1 -> {
                tab.selected = false
                tab.webView?.onPause()
                flWebViewContainer.removeView(tab.webView)
                viewModel.currentTab.value = null
            }

            position == viewModel.tabsStates.size - 1 -> changeTab(viewModel.tabsStates[position - 1])

            else -> changeTab(viewModel.tabsStates[position + 1])
        }
        viewModel.tabsStates.remove(tab)
        tabsAdapter?.notifyDataSetChanged()

        tab.removeFiles(this)
    }

    private fun changeTab(newTab: WebTabState) {
        if (viewModel.currentTab.value != null) {
            viewModel.currentTab.value!!.selected = false
            viewModel.currentTab.value!!.webView?.onPause()
            flWebViewContainer!!.removeView(viewModel.currentTab.value!!.webView)
        }

        newTab.selected = true
        viewModel.currentTab.value = newTab
        tabsAdapter!!.notifyDataSetChanged()
        if (llMenuOverlay.visibility == View.GONE) {
            lvTabs.setSelection(viewModel.tabsStates.indexOf(newTab))
        }
        if (viewModel.currentTab.value!!.webView == null) {
            createWebView(viewModel.currentTab.value!!)
            viewModel.currentTab.value!!.restoreWebView()
            flWebViewContainer!!.addView(viewModel.currentTab.value!!.webView)
        } else {
            flWebViewContainer!!.addView(viewModel.currentTab.value!!.webView)
            viewModel.currentTab.value!!.webView?.onResume()
        }
        viewModel.currentTab.value!!.webView?.setNetworkAvailable(Utils.isNetworkConnected(this))

        etUrl!!.setText(newTab.currentOriginalUrl)
        ibBack!!.isEnabled = newTab.webView?.canGoBack() == true
        ibForward!!.isEnabled = newTab.webView?.canGoForward() == true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: WebTabState) {
        tab.webView = WebViewEx(this)

        if (settingsViewModel.uaString.value == null || settingsViewModel.uaString.value == "") {
            settingsViewModel.saveUAString("TV Bro/1.0 " + tab.webView!!.settings.userAgentString.replace("Mobile Safari", "Safari"))
        }
        tab.webView!!.settings.userAgentString = settingsViewModel.uaString.value

        tab.webView?.addJavascriptInterface(viewModel.jsInterface, "TVBro")

        tab.webView?.setListener(object : WebViewEx.Listener {
            override fun onThumbnailReady(thumbnail: Bitmap) {
                tab.thumbnail = thumbnail
                tabsAdapter!!.notifyDataSetChanged()
            }

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
        })

        tab.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return super.onJsAlert(view, url, message, result)
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                tab.webView?.visibility = View.GONE
                flFullscreenContainer!!.visibility = View.VISIBLE
                flFullscreenContainer!!.addView(view)

                fullScreenView = view
                fullscreenViewCallback = callback
            }

            override fun onHideCustomView() {
                if (fullScreenView != null) {
                    flFullscreenContainer.removeView(fullScreenView)
                    fullScreenView = null
                }

                fullscreenViewCallback!!.onCustomViewHidden()

                flFullscreenContainer.visibility = View.GONE
                tab.webView?.visibility = View.VISIBLE
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.visibility = View.VISIBLE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    progressBar.setProgress(newProgress, true)
                } else {
                    progressBar.progress = newProgress
                }
                uiHandler!!.removeCallbacks(progressBarHideRunnable)
                if (newProgress == 100) {
                    uiHandler!!.postDelayed(progressBarHideRunnable, 1000)
                } else {
                    uiHandler!!.postDelayed(progressBarHideRunnable, 5000)
                }
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                super.onReceivedTitle(view, title)
                tab.currentTitle = title
                tabsAdapter!!.notifyDataSetChanged()
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
                                    val permissionsArr = arrayOfNulls<String>(neededPermissions.size)
                                    neededPermissions.toTypedArray()
                                    requestPermissions(permissionsArr,
                                            MY_PERMISSIONS_REQUEST_WEB_PAGE_PERMISSIONS)
                                } else {
                                    if (reuestedResourcesForAlreadyGrantedPermissions!!.isEmpty()) {
                                        webPermissionsRequest.deny()
                                    } else {
                                        val grantedResourcesArr = arrayOfNulls<String>(reuestedResourcesForAlreadyGrantedPermissions!!.size)
                                        reuestedResourcesForAlreadyGrantedPermissions!!.toTypedArray()
                                        webPermissionsRequest.grant(grantedResourcesArr)
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

        tab.webView?.webChromeClient = tab.webChromeClient

        tab.webView?.webViewClient = object : WebViewClient() {
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
                etUrl.setText(tab.currentOriginalUrl)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (tab.webView == null || viewModel.currentTab.value == null || view == null) {
                    return
                }
                ibBack!!.isEnabled = tab.webView?.canGoBack() == true
                ibForward!!.isEnabled = tab.webView?.canGoForward() == true
                tab.webView?.setNeedThumbnail(thumbnailesSize)
                tab.webView?.postInvalidate()
                if (tab.webView?.url != null) {
                    tab.currentOriginalUrl = tab.webView?.url
                } else if (url != null) {
                    tab.currentOriginalUrl = url
                }
                etUrl!!.setText(tab.currentOriginalUrl)

                tab.webView?.evaluateJavascript(Scripts.INITIAL_SCRIPT, null)
                viewModel.currentTab.value!!.webPageInteractionDetected = false
                if (WebViewEx.HOME_URL == url) {
                    view.loadUrl("javascript:renderSuggestions()")
                }
            }

            override fun onLoadResource(view: WebView, url: String) {
                super.onLoadResource(view, url)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                showCertificateErrorHint(error)
                handler.proceed()
            }
        }

        tab.webView?.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            if (focused && llMenuOverlay.visibility == View.VISIBLE) {
                hideMenuOverlay()
            }
        }

        tab.webView?.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            onDownloadRequested(url, DownloadUtils.guessFileName(url, contentDisposition, mimetype), userAgent
                    ?: tab.webView?.settings?.userAgentString)
        }
    }

    private fun showCertificateErrorHint(error: SslError) {
        llCertificateWarning.visibility = View.VISIBLE
        llCertificateWarning.alpha = 1f
        etUrl.setTextColor(Color.RED)
        uiHandler.removeCallbacks(hideCertificateWarningRunnable)
        uiHandler.postDelayed(hideCertificateWarningRunnable, 10000)
    }

    private val hideCertificateWarningRunnable = object : Runnable {
        override fun run() {
            llCertificateWarning.animate().alpha(0f).setDuration(1000)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator?) {
                            llCertificateWarning.visibility = View.GONE
                        }
                    }).start()
        }
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
                    val resourcesArr = arrayOfNulls<String>(resources.size)
                    resources.toTypedArray()
                    webPermissionsRequest!!.grant(resourcesArr)
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
        if (llMenuOverlay.visibility == View.GONE) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val shortcutMgr = ShortcutMgr.getInstance()
        val keyCode = if (event.keyCode != 0) event.keyCode else event.scanCode

        if (keyCode == KeyEvent.KEYCODE_BACK && viewModel.currentTab.value != null && fullScreenView != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                //nop
            } else if (event.action == KeyEvent.ACTION_UP) {
                uiHandler?.post {
                    viewModel.currentTab.value!!.webChromeClient?.onHideCustomView()
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_BACK && flWebViewContainer!!.zoomMode) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                //nop
            } else if (event.action == KeyEvent.ACTION_UP) {
                flWebViewContainer!!.exitZoomMode()
            }
            return true
        } else if (shortcutMgr.canProcessKeyCode(keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                //nop
            } else if (event.action == KeyEvent.ACTION_UP) {
                shortcutMgr.process(keyCode, this)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showMenuOverlay() {
        llMenuOverlay.visibility = View.VISIBLE

        val anim = AnimationUtils.loadAnimation(this, R.anim.menu_in_anim)
        anim.setAnimationListener(object : BaseAnimationListener() {
            override fun onAnimationEnd(animation: Animation) {
                ibMenu!!.requestFocus()
                flMenuRightContainer.alpha = 0f
                flMenuRightContainer.animate().alpha(0.2f).start()
            }
        })
        llMenu.startAnimation(anim)

        llActionBar.visibility = View.VISIBLE
        llActionBar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.actionbar_in_anim))
    }

    private fun hideMenuOverlay() {
        if (llMenuOverlay.visibility == View.GONE) {
            return
        }
        var anim = AnimationUtils.loadAnimation(this, R.anim.menu_out_anim)
        anim.setAnimationListener(object : BaseAnimationListener() {
            override fun onAnimationEnd(animation: Animation) {
                llMenuOverlay.visibility = View.GONE
                if (viewModel.currentTab.value != null) {
                    viewModel.currentTab.value!!.webView?.requestFocus()
                }
            }
        })
        llMenu.startAnimation(anim)

        flMenuRightContainer.animate().alpha(0f).start()

        anim = AnimationUtils.loadAnimation(this, android.R.anim.fade_out)
        anim.setAnimationListener(object : BaseAnimationListener() {
            override fun onAnimationEnd(animation: Animation) {
                llActionBar!!.visibility = View.GONE
            }
        })
        llActionBar.startAnimation(anim)
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
        viewModel.initiateVoiceSearch(this)
    }
}
