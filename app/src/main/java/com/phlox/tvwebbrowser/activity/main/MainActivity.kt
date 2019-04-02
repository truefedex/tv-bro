package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.arch.lifecycle.ViewModelProviders
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.speech.RecognizerIntent
import android.support.v4.app.FragmentActivity
import android.text.TextUtils
import android.util.Log
import android.util.Patterns
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import com.phlox.asql.ASQL
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity
import com.phlox.tvwebbrowser.activity.history.HistoryActivity
import com.phlox.tvwebbrowser.activity.main.adapter.TabsListAdapter
import com.phlox.tvwebbrowser.activity.main.dialogs.FavoritesDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.SearchEngineConfigDialogFactory
import com.phlox.tvwebbrowser.activity.main.dialogs.ShortcutDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.UserAgentConfigDialogFactory
import com.phlox.tvwebbrowser.activity.main.view.CursorLayout
import com.phlox.tvwebbrowser.activity.main.view.Scripts
import com.phlox.tvwebbrowser.activity.main.view.WebTabItemView
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.model.*
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.utils.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.*


class MainActivity : FragmentActivity(), CoroutineScope by MainScope() {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private val VOICE_SEARCH_REQUEST_CODE = 10001
        private val MY_PERMISSIONS_REQUEST_WEB_PAGE_PERMISSIONS = 10002
        private val MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS = 10003
        private val MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 10004
        private val PICKFILE_REQUEST_CODE = 10005
        private val REQUEST_CODE_HISTORY_ACTIVITY = 10006
        val STATE_JSON = "state.json"
        val SEARCH_ENGINE_URL_PREF_KEY = "search_engine_url"
        val USER_AGENT_PREF_KEY = "user_agent"
        val MAIN_PREFS_NAME = "main.xml"
    }

    private lateinit var viewModel: MainActivityViewModel
    private var handler: Handler? = null
    private var currentTab: WebTabState? = null
    private val tabsStates = ArrayList<WebTabState>()
    private var tabsAdapter: TabsListAdapter? = null
    private var thumbnailesSize: Size? = null
    private var fullscreenViewCallback: WebChromeClient.CustomViewCallback? = null
    private var permRequestDialog: AlertDialog? = null
    private var webPermissionsRequest: PermissionRequest? = null
    private var reuestedResourcesForAlreadyGrantedPermissions: ArrayList<String>? = null
    private var geoPermissionOrigin: String? = null
    private var geoPermissionsCallback: GeolocationPermissions.Callback? = null
    private var running: Boolean = false
    private var urlToDownload: String? = null
    private var originalDownloadFileName: String? = null
    private var userAgentForDownload: String? = null
    private var pickFileCallback: ValueCallback<Array<Uri>>? = null
    private val jsInterface = AndroidJSInterface()
    private lateinit var asql: ASQL
    private var lastHistoryItem: HistoryItem? = null
    private var searchEngineURL: String? = null
    private var downloadsService: DownloadService? = null
    private var downloadAnimation: Animation? = null

    private var fullScreenView: View? = null
    private var popupMenuMoreActions: PopupMenu? = null
    private var prefs: SharedPreferences? = null

    private val webTabStates: List<WebTabState>
        get() {
            val tabsStates = ArrayList<WebTabState>()
            try {
                val fis = openFileInput(STATE_JSON)
                val storeStr = StringUtils.streamToString(fis)
                val store = JSONObject(storeStr)
                val tabsStore = store.getJSONArray("tabs")
                for (i in 0 until tabsStore.length()) {
                    val tab = WebTabState(this@MainActivity, tabsStore.getJSONObject(i))
                    tabsStates.add(tab)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return tabsStates
        }

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
            if (currentTab != null) {
                currentTab!!.webView?.setNetworkAvailable(isConnected)
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
            if (currentTab != null) {
                currentTab!!.webView?.setNeedThumbnail(thumbnailesSize)
                currentTab!!.webView?.postInvalidate()
            }
        }
    }

    internal var onMenuMoreItemClickListener: PopupMenu.OnMenuItemClickListener = PopupMenu.OnMenuItemClickListener { item ->
        when (item.itemId) {
            R.id.miSearchEngine -> {
                SearchEngineConfigDialogFactory.show(this@MainActivity, searchEngineURL!!, prefs!!, true, object : SearchEngineConfigDialogFactory.Callback {
                    override fun onDone(url: String) {
                        searchEngineURL = url
                    }
                })
                hideMenuOverlay()
                true
            }
            R.id.miUserAgent -> {
                hideMenuOverlay()
                if (currentTab == null) {
                    return@OnMenuItemClickListener true
                }
                var uaString = currentTab!!.webView?.settings?.userAgentString
                if (WebViewEx.defaultUAString == uaString) {
                    uaString = ""
                }
                UserAgentConfigDialogFactory.show(this@MainActivity, uaString!!, object : UserAgentConfigDialogFactory.Callback {
                    override fun onDone(defaultUAString: String?) {
                        val editor = prefs!!.edit()
                        editor.putString(USER_AGENT_PREF_KEY, defaultUAString)
                        editor.apply()
                        for (tab in tabsStates) {
                            if (tab.webView != null) {
                                tab.webView?.settings?.userAgentString = defaultUAString
                            }
                        }
                        refresh()
                    }
                })

                true
            }
            R.id.miShortcutMenu, R.id.miShortcutNavigateBack, R.id.miShortcutNavigateHome, R.id.miShortcutRefreshPage, R.id.miShortcutVoiceSearch -> {
                ShortcutDialog(this@MainActivity,
                        ShortcutMgr.getInstance(this@MainActivity)
                                .findForMenu(item.itemId)!!
                ).show()
                true
            }
            else -> false
        }
    }

    fun showHistory() {
        startActivityForResult(
                Intent(this@MainActivity, HistoryActivity::class.java),
                REQUEST_CODE_HISTORY_ACTIVITY)
        hideMenuOverlay()
    }

    fun showFavorites() {
        val currentPageTitle = if (currentTab != null) currentTab!!.currentTitle else ""
        val currentPageUrl = if (currentTab != null) currentTab!!.currentOriginalUrl else ""
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
        handler = Handler()
        jsInterface.setActivity(this)
        setContentView(R.layout.activity_main)
        AndroidBug5497Workaround.assistActivity(this)

        llMenuOverlay.visibility = View.GONE
        llActionBar.visibility = View.GONE
        progressBar.visibility = View.GONE

        tabsAdapter = TabsListAdapter(tabsStates, tabsEventsListener)
        lvTabs.adapter = tabsAdapter
        lvTabs.itemsCanFocus = true

        flWebViewContainer.setCallback(object : CursorLayout.Callback {
            override fun onUserInteraction() {
                val tab = currentTab
                if (tab != null) {
                    if (!tab.webPageInteractionDetected) {
                        tab.webPageInteractionDetected = true
                        logVisitedHistory(tab.currentTitle, tab.currentOriginalUrl, tab.faviconHash)
                    }
                }
            }
        })

        btnNewTab.setOnClickListener { openInNewTab(WebViewEx.HOME_URL) }

        val pm = packageManager
        val activities = pm.queryIntentActivities(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        if (activities.size == 0) {
            ibVoiceSearch.visibility = View.GONE
        }

        ibVoiceSearch.setOnClickListener { initiateVoiceSearch() }

        /*ibHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                navigate(HOME_URL);
            }
        });*/
        ibBack.setOnClickListener { navigateBack() }
        ibForward.setOnClickListener {
            if (currentTab != null && (currentTab!!.webView?.canGoForward() == true)) {
                currentTab!!.webView?.goForward()
            }
        }
        ibRefresh.setOnClickListener { refresh() }

        ibMenu.setOnClickListener { finish() }

        ibDownloads.setOnClickListener { startActivity(Intent(this@MainActivity, DownloadsActivity::class.java)) }

        ibFavorites.setOnClickListener { showFavorites() }

        ibHistory.setOnClickListener { showHistory() }

        ibMore.setOnClickListener {
            if (popupMenuMoreActions == null) {
                popupMenuMoreActions = PopupMenu(this@MainActivity, ibMore, Gravity.BOTTOM)
                popupMenuMoreActions!!.inflate(R.menu.action_more)
                popupMenuMoreActions!!.setOnMenuItemClickListener(onMenuMoreItemClickListener)
            }
            popupMenuMoreActions!!.show()
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
                handler!!.postDelayed(//workaround an android TV bug
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
                        currentTab!!.webView?.requestFocus()
                    }
                    return@OnKeyListener true
                }
            }
            false
        })

        asql = ASQL.getDefault(applicationContext)

        loadState()
    }

    fun navigateBack() {
        if (currentTab != null && currentTab!!.webView?.canGoBack() == true) {
            currentTab!!.webView?.goBack()
        }
    }

    fun refresh() {
        if (currentTab != null) {
            currentTab!!.webView?.reload()
        }
    }

    override fun onDestroy() {
        jsInterface.setActivity(null)
        super.onDestroy()
    }

    override fun onStop() {
        saveState()
        super.onStop()
    }

    private fun saveState() = launch(Dispatchers.Main) {
        WebTabState.saveTabs(this@MainActivity, tabsStates)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val intentUri = intent.data
        if (intentUri != null) {
            openInNewTab(intentUri.toString())
        }
    }

    private fun loadState() {
        progressBarGeneric.visibility = View.VISIBLE
        progressBarGeneric.requestFocus()
        object : Thread() {
            override fun run() {
                initHistory()

                val tabsStates = webTabStates
                val onTabsLoadedRunnable = OnTabsLoadedRunnable(tabsStates)
                runOnUiThread(onTabsLoadedRunnable)
            }
        }.start()

        prefs = getSharedPreferences(MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        searchEngineURL = prefs!!.getString(SEARCH_ENGINE_URL_PREF_KEY, "")
        if ("" == searchEngineURL) {
            SearchEngineConfigDialogFactory.show(this, searchEngineURL!!, prefs!!, false,
                    object : SearchEngineConfigDialogFactory.Callback {
                        override fun onDone(url: String) {
                            searchEngineURL = url
                        }
                    })
        }
    }

    private fun initHistory() {
        val count = asql.count(HistoryItem::class.java)
        if (count > 5000) {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, -3)
            asql.db.delete("history", "time < ?", arrayOf(java.lang.Long.toString(c.time.time)))
        }
        try {
            val result = asql.queryAll<HistoryItem>(HistoryItem::class.java, "SELECT * FROM history ORDER BY time DESC LIMIT 1")
            if (!result.isEmpty()) {
                lastHistoryItem = result.get(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val frequentlyUsedUrls = asql.queryAll<HistoryItem>(HistoryItem::class.java,
                    "SELECT title, url, favicon, count(url) as cnt , max(time) as time FROM history GROUP BY title, url, favicon ORDER BY cnt DESC, time DESC LIMIT 6")
            jsInterface.setSuggestions(this, frequentlyUsedUrls)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun openInNewTab(url: String?) {
        if (url == null) {
            return
        }
        val tab = WebTabState()
        tab.currentOriginalUrl = url
        createWebView(tab)
        tabsStates.add(0, tab)
        changeTab(tab)
        navigate(url)
    }

    private fun closeTab(tab: WebTabState?) {
        if (tab == null) return
        val position = tabsStates.indexOf(tab)
        when {
            tabsStates.size == 1 -> {
                tab.selected = false
                tab.webView?.onPause()
                flWebViewContainer.removeView(tab.webView)
                currentTab = null
            }

            position == tabsStates.size - 1 -> changeTab(tabsStates[position - 1])

            else -> changeTab(tabsStates[position + 1])
        }
        tabsStates.remove(tab)
        tabsAdapter?.notifyDataSetChanged()

        tab.removeFiles(this)
    }

    private fun changeTab(newTab: WebTabState) {
        if (currentTab != null) {
            currentTab!!.selected = false
            currentTab!!.webView?.onPause()
            flWebViewContainer!!.removeView(currentTab!!.webView)
        }

        newTab.selected = true
        currentTab = newTab
        tabsAdapter!!.notifyDataSetChanged()
        if (currentTab!!.webView == null) {
            createWebView(currentTab!!)
            currentTab!!.restoreWebView()
            flWebViewContainer!!.addView(currentTab!!.webView)
        } else {
            flWebViewContainer!!.addView(currentTab!!.webView)
            currentTab!!.webView?.onResume()
        }
        currentTab!!.webView?.setNetworkAvailable(Utils.isNetworkConnected(this))

        etUrl!!.setText(newTab.currentOriginalUrl)
        ibBack!!.isEnabled = newTab.webView?.canGoBack() == true
        ibForward!!.isEnabled = newTab.webView?.canGoForward() == true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: WebTabState) {
        tab.webView = WebViewEx(this)
        tab.webView?.addJavascriptInterface(jsInterface, "TVBro")

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
                this@MainActivity.onDownloadRequested(url, fileName
                        ?: "url.html", tab.webView?.uaString)
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
                handler!!.removeCallbacks(progressBarHideRunnable)
                if (newProgress == 100) {
                    handler!!.postDelayed(progressBarHideRunnable, 1000)
                } else {
                    handler!!.postDelayed(progressBarHideRunnable, 5000)
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
                val currentTab = this@MainActivity.currentTab
                val index = if (currentTab == null) 0 else tabsStates.indexOf(currentTab)
                tabsStates.add(index, tab)
                changeTab(tab)
                (resultMsg.obj as WebView.WebViewTransport).webView = tab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                for (tab in tabsStates) {
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
                if (tab.webView == null || currentTab == null || view == null) {
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
                currentTab!!.webPageInteractionDetected = false
                if (WebViewEx.HOME_URL == url) {
                    view.loadUrl("javascript:renderSuggestions()")
                }
            }

            override fun onLoadResource(view: WebView, url: String) {
                super.onLoadResource(view, url)
            }
        }

        tab.webView?.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            if (focused && llMenuOverlay.visibility == View.VISIBLE) {
                hideMenuOverlay()
            }
        }

        tab.webView?.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            onDownloadRequested(url, DownloadUtils.guessFileName(url, contentDisposition, mimetype), userAgent
                    ?: tab.webView?.uaString)
        }
    }

    private fun onDownloadRequested(url: String, originalDownloadFileName: String?, userAgent: String?) {
        this.urlToDownload = url
        this.originalDownloadFileName = originalDownloadFileName
        this.userAgentForDownload = userAgent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE)
        } else {
            startDownload(url, originalDownloadFileName!!, userAgent)
        }
    }

    private fun logVisitedHistory(title: String?, url: String?, faviconHash: String?) {
        if (url != null && (lastHistoryItem != null && url == lastHistoryItem!!.url || url == WebViewEx.HOME_URL)) {
            return
        }

        val item = HistoryItem()
        item.url = url
        item.title = title ?: ""
        item.time = Date().time
        item.favicon = faviconHash
        lastHistoryItem = item
        asql.execInsert("INSERT INTO history (time, title, url, favicon) VALUES (:time, :title, :url, :favicon)", lastHistoryItem) { lastInsertRowId, exception ->
            if (exception != null) {
                Log.e(TAG, exception.toString())
            } else {
                //good!
            }
        }
    }

    private fun startDownload(url: String, originalFileName: String, userAgent: String?) {
        val extPos = originalFileName.lastIndexOf(".")
        val hasExt = extPos != -1
        var ext: String? = null
        var prefix: String? = null
        if (hasExt) {
            ext = originalFileName.substring(extPos + 1)
            prefix = originalFileName.substring(0, extPos)
        }
        var fileName = originalFileName
        var i = 0
        while (File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + File.separator + fileName).exists()) {
            i++
            if (hasExt) {
                fileName = prefix + "_(" + i + ")." + ext
            } else {
                fileName = originalFileName + "_(" + i + ")"
            }
        }

        if (Environment.MEDIA_MOUNTED != Environment.getExternalStorageState()) {
            Toast.makeText(this, R.string.storage_not_mounted, Toast.LENGTH_SHORT).show()
            return
        }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            Toast.makeText(this, R.string.can_not_create_downloads, Toast.LENGTH_SHORT).show()
            return
        }
        val fullDestFilePath = downloadsDir.toString() + File.separator + fileName
        downloadsService!!.startDownloading(url, fullDestFilePath, fileName, userAgent!!)

        Utils.showToast(this, getString(R.string.download_started,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + File.separator + fileName))
        showMenuOverlay()
        if (downloadAnimation == null) {
            downloadAnimation = AnimationUtils.loadAnimation(this, R.anim.infinite_fadeinout_anim)
            ibDownloads.startAnimation(downloadAnimation)
        }
    }

    override fun onTrimMemory(level: Int) {
        for (tab in tabsStates) {
            if (!tab.selected) {
                tab.recycleWebView()
            }
        }
        super.onTrimMemory(level)
    }

    fun initiateVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speak))
        try {
            startActivityForResult(intent, VOICE_SEARCH_REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.voice_search_not_found, Toast.LENGTH_SHORT).show()
        }

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
                val urlToDownload = this.urlToDownload
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED && urlToDownload != null) {
                    startDownload(urlToDownload, originalDownloadFileName!!, userAgentForDownload)
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

            else -> super.onActivityResult(requestCode, resultCode, data)
        }

    }

    override fun onResume() {
        running = true
        super.onResume()
        val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        registerReceiver(mConnectivityChangeReceiver, intentFilter)
        if (currentTab != null) {
            currentTab!!.webView?.onResume()
        }
        bindService(Intent(this, DownloadService::class.java), downloadsServiceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        unbindService(downloadsServiceConnection)
        if (currentTab != null) {
            currentTab!!.webView?.onPause()
        }
        if (mConnectivityChangeReceiver != null) unregisterReceiver(mConnectivityChangeReceiver)
        super.onPause()
        running = false
    }

    fun navigate(url: String) {
        if (currentTab != null) {
            currentTab!!.webView?.loadUrl(url)
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

            val searchUrl = searchEngineURL!!.replace("[query]", query!!)
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
        val shortcutMgr = ShortcutMgr.getInstance(this)
        val keyCode = if (event.keyCode != 0) event.keyCode else event.scanCode

        if (keyCode == KeyEvent.KEYCODE_BACK && currentTab != null && fullScreenView != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                //nop
            } else if (event.action == KeyEvent.ACTION_UP) {
                handler?.post {
                    currentTab!!.webChromeClient?.onHideCustomView()
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
                if (currentTab != null) {
                    currentTab!!.webView?.requestFocus()
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

    internal inner class OnTabsLoadedRunnable(private val tabsStatesLoaded: List<WebTabState>) : Runnable {

        override fun run() {
            val intentUri = intent.data
            progressBarGeneric.visibility = View.GONE
            if (!running) {
                if (!tabsStatesLoaded.isEmpty()) {
                    this@MainActivity.tabsStates.addAll(tabsStatesLoaded)
                }
                return
            }
            if (tabsStatesLoaded.isEmpty()) {
                if (intentUri == null) {
                    openInNewTab(WebViewEx.HOME_URL)
                }
            } else {
                this@MainActivity.tabsStates.addAll(tabsStatesLoaded)
                for (i in tabsStatesLoaded.indices) {
                    val tab = tabsStatesLoaded[i]
                    if (tab.selected) {
                        changeTab(tab)
                        break
                    }
                }
            }
            if (intentUri != null) {
                openInNewTab(intentUri.toString())
            }
        }
    }
}
