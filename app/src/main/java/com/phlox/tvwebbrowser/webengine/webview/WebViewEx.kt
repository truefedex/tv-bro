package com.phlox.tvwebbrowser.webengine.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaDrm
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.utils.LogUtils
import java.net.URLEncoder
import java.util.*


/**
 * Copyright (c) 2016 Fedir Tsapana.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class WebViewEx(context: Context, val callback: Callback, val jsInterface: AndroidJSInterface) : WebView(context) {
    companion object {
        val TAG = WebViewEx::class.java.simpleName
        const val WEB_VIEW_TAG = "TV Bro WebView"
        const val INTERNAL_SCHEME = "internal://"
        const val INTERNAL_SCHEME_WARNING_DOMAIN = "warning"
        const val INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT = "certificate"
        val WIDEVINE_UUID = UUID(-0x121074568629b532L,-0x5c37d8232ae2de13L)
    }

    private var genericInjects: String? = null
    private var webChromeClient_: WebChromeClient
    private var fullscreenViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pickFileCallback: ValueCallback<Array<Uri>>? = null
    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0
    private var permRequestDialog: AlertDialog? = null
    private var webPermissionsRequest: PermissionRequest? = null
    private var requestedWebResourcesThatDoNotNeedToGrantAndroidPermissions: ArrayList<String>? = null
    private var geoPermissionOrigin: String? = null
    private var geoPermissionsCallback: GeolocationPermissions.Callback? = null
    var lastSSLError: SslError? = null
    var trustSsl: Boolean = false
    private var currentOriginalUrl: Uri? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    interface Callback {
        fun getActivity(): Activity?
        fun onOpenInNewTabRequested(url: String)
        fun onDownloadRequested(url: String)
        fun onLongTap()
        fun onThumbnailError()
        fun onShowCustomView(view: View)
        fun onHideCustomView()
        fun onProgressChanged(newProgress: Int)
        fun onReceivedTitle(title: String)
        fun onShowFileChooser(intent: Intent): Boolean
        fun onReceivedIcon(icon: Bitmap)
        fun requestPermissions(array: Array<String>, geo: Boolean)
        fun shouldOverrideUrlLoading(url: String): Boolean
        fun onPageStarted(url: String?)
        fun onPageFinished(url: String?)
        fun onPageCertificateError(url: String?)
        fun isAdBlockingEnabled(): Boolean
        fun isDialogsBlockingEnabled(): Boolean
        fun isAd(request: WebResourceRequest, baseUri: Uri): Boolean
        fun onBlockedAd(url: Uri)
        fun onBlockedDialog(newTab: Boolean)
        fun onCreateWindow(dialog: Boolean, userGesture: Boolean): WebViewEx?
        fun closeWindow(window: WebView)
        fun onDownloadStart(url: String, userAgent: String, contentDisposition: String, mimetype: String?, contentLength: Long)
        fun onScaleChanged(oldScale: Float, newScale: Float)
        fun onCopyTextToClipboardRequested(url: String)
        fun onShareUrlRequested(url: String)
        fun onOpenInExternalAppRequested(url: String)
        fun onVisited(url: String)
        fun suggestActionsForLink(href: String, x: Int, y: Int)
    }

    init {
        with(settings) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = callback.isAdBlockingEnabled()
            }
            javaScriptEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            saveFormData = true
            setSupportZoom(true)
            domStorageEnabled = true
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setNeedInitialFocus(false)

            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            domStorageEnabled = true
            if (BuildConfig.DEBUG) {
                setWebContentsDebuggingEnabled(true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                        Configuration.UI_MODE_NIGHT_YES -> {
                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
                        }
                        Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_UNDEFINED -> {
                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
                        }
                    }
                }
            } else {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                        Configuration.UI_MODE_NIGHT_YES -> {
                            WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_ON)
                        }
                        Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_UNDEFINED -> {
                            WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_OFF)
                        }
                        else -> {
                            WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_AUTO)
                        }
                    }
                }
            }
        }

        /*scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
        isScrollbarFadingEnabled = false*/

        setOnLongClickListener {
            evaluateJavascript(Scripts.LONG_PRESS_SCRIPT) { href ->
                if (href != null && "null" != href) {
                    callback.suggestActionsForLink(href, lastTouchX, lastTouchY)
                } else {
                    callback.onLongTap()
                }
            }
            true
        }

        webChromeClient_ = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return if (callback.isDialogsBlockingEnabled()) {
                    callback.onBlockedDialog(false)
                    result.cancel()
                    true
                } else super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return if (callback.isDialogsBlockingEnabled()) {
                    callback.onBlockedDialog(false)
                    result.cancel()
                    true
                } else super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
                return if (callback.isDialogsBlockingEnabled()) {
                    callback.onBlockedDialog(false)
                    result.cancel()
                    true
                } else super.onJsPrompt(view, url, message, defaultValue, result)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                this@WebViewEx.callback.onShowCustomView(view)
                fullscreenViewCallback = callback
            }

            override fun onHideCustomView() {
                callback.onHideCustomView()
                fullscreenViewCallback?.onCustomViewHidden()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                callback.onProgressChanged(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                callback.onReceivedTitle(title)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.resources.size == 1 &&
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID == request.resources[0]) {
                    //fast path for grant/deny RESOURCE_PROTECTED_MEDIA_ID
                    if (MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)) {
                        val widevineKeyDrm = MediaDrm(WIDEVINE_UUID)
                        val version = widevineKeyDrm.getPropertyString(MediaDrm.PROPERTY_VERSION)
                        Log.i(TAG, "DRM widevine version = " + version)
                        request.grant(request.resources)
                    } else {
                        request.deny()
                    }
                    return
                }

                val activity = callback.getActivity() ?: return
                webPermissionsRequest = request
                permRequestDialog = AlertDialog.Builder(activity)
                        .setMessage(activity.getString(R.string.web_perm_request_confirmation, TextUtils.join("\n", request.resources)))
                        .setCancelable(false)
                        .setNegativeButton(R.string.deny) { _, _ ->
                            webPermissionsRequest?.deny()
                            permRequestDialog = null
                            webPermissionsRequest = null
                        }
                        .setPositiveButton(R.string.allow) { dialog, which ->
                            val webPermissionsRequest = this@WebViewEx.webPermissionsRequest
                            this@WebViewEx.webPermissionsRequest = null
                            if (webPermissionsRequest == null) {
                                return@setPositiveButton
                            }

                            val neededPermissions = ArrayList<String>()
                            val resourcesThatDoNotNeedToGrantPerms = ArrayList<String>()
                            for (resource in webPermissionsRequest.resources) {
                                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE == resource) {
                                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        neededPermissions.add(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        resourcesThatDoNotNeedToGrantPerms.add(resource)
                                    }
                                } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE == resource) {
                                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                        neededPermissions.add(Manifest.permission.CAMERA)
                                    } else {
                                        resourcesThatDoNotNeedToGrantPerms.add(resource)
                                    }
                                } else {
                                    resourcesThatDoNotNeedToGrantPerms.add(resource)
                                }
                            }

                            if (neededPermissions.isNotEmpty()) {
                                requestedWebResourcesThatDoNotNeedToGrantAndroidPermissions = resourcesThatDoNotNeedToGrantPerms
                                callback.requestPermissions(neededPermissions.toTypedArray(), false)
                            } else {
                                webPermissionsRequest.grant(webPermissionsRequest.resources)
                            }

                            permRequestDialog = null
                        }
                        .create()
                permRequestDialog!!.show()
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                permRequestDialog?.apply {
                    dismiss()
                    permRequestDialog = null
                }
                webPermissionsRequest = null
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val activity = this@WebViewEx.callback.getActivity() ?: return
                geoPermissionOrigin = origin
                geoPermissionsCallback = callback
                permRequestDialog = AlertDialog.Builder(activity)
                        .setMessage(activity.getString(R.string.web_perm_request_confirmation, activity.getString(R.string.location)))
                        .setCancelable(false)
                        .setNegativeButton(R.string.deny) { dialog, which ->
                            geoPermissionsCallback!!.invoke(geoPermissionOrigin, false, false)
                            permRequestDialog = null
                            geoPermissionsCallback = null
                        }
                        .setPositiveButton(R.string.allow) { dialog, which ->
                            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                this@WebViewEx.callback.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), true)
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
                val msg: String = "(" + consoleMessage.sourceId() + "[" + consoleMessage.lineNumber() + "]): " + consoleMessage.message()
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(WEB_VIEW_TAG, msg)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(WEB_VIEW_TAG, msg)
                    else -> Log.i(WEB_VIEW_TAG, msg)
                }
                return true
            }


            override fun onShowFileChooser(mWebView: WebView, callback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
                pickFileCallback = callback

                val result = this@WebViewEx.callback.onShowFileChooser(fileChooserParams.createIntent())
                if (!result) {
                    pickFileCallback = null
                }
                return result
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap) {
                Log.d(TAG, "onReceivedIcon: ${icon.width}x${icon.height}")
                callback.onReceivedIcon(icon)
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val webView = callback.onCreateWindow(isDialog, isUserGesture) ?: return false
                (resultMsg.obj as WebView.WebViewTransport).webView = webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                callback.closeWindow(window)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                Log.d(TAG, "shouldOverrideUrlLoading url: ${request.url}")
                return callback.shouldOverrideUrlLoading(request.url.toString())
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                //Log.d(TAG, "shouldInterceptRequest url: ${request.url}")
                val currentOriginalUrl = currentOriginalUrl

                if (currentOriginalUrl != null && currentOriginalUrl.toString() == Config.HOME_PAGE_URL) {
                    HomePageHelper.shouldInterceptRequest(view, request)?.let {
                        return it
                    }
                }

                if (!callback.isAdBlockingEnabled()) {
                    return super.shouldInterceptRequest(view, request)
                }

                val ad = currentOriginalUrl?.let { callback.isAd(request, it)} ?: false
                return if (ad) {
                    Log.d(TAG, "Blocked ads request: ${request.url}")
                    uiHandler.post { callback.onBlockedAd(request.url) }
                    WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                } else super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted url: $url")
                currentOriginalUrl = Uri.parse(url)
                callback.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished url: $url")
                callback.onPageFinished(url)
                evaluateJavascript(getGenericJSInjects(), null)
            }

            override fun onLoadResource(view: WebView, url: String) {
                super.onLoadResource(view, url)
                //Log.d(TAG, "onLoadResource url: $url")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                Log.e(TAG, "onReceivedSslError url: ${error.url}")
                if (trustSsl && lastSSLError?.certificate?.toString()?.equals(error.certificate.toString()) == true) {
                    trustSsl = false
                    lastSSLError = null
                    handler.proceed()
                    return
                }
                handler.cancel()
                val errUrl = error.url ?: return
                val origUrl = currentOriginalUrl ?: return
                if (Uri.parse(errUrl).host == origUrl.host) {//skip ssl errors during loading non-page resources (Chrome did like this too)
                    showCertificateErrorPage(error)
                }
            }

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                super.onScaleChanged(view, oldScale, newScale)
                callback.onScaleChanged(oldScale, newScale)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String, isReload: Boolean) {
                if (!isReload) {
                    callback.onVisited(url)
                }
            }
        }

        webChromeClient = webChromeClient_

        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.i(TAG, "DownloadListener.onDownloadStart url: $url")
            if (url.startsWith("blob:")) {
                //nop. we handle this by injected js on onPageFinished
            } else {
                callback.onDownloadStart(url, userAgent, contentDisposition, mimetype, contentLength)
            }
        }

        addJavascriptInterface(jsInterface, "TVBro")
    }

    private fun showCertificateErrorPage(error: SslError) {
        callback.onPageCertificateError(error.url)
        lastSSLError = error
        val url = INTERNAL_SCHEME + INTERNAL_SCHEME_WARNING_DOMAIN +
                "?type=" + INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT +
                "&url=" + URLEncoder.encode(error.url, "UTF-8")
        loadUrl(url)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.action
        when (action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                lastTouchX = event.x.toInt()
                lastTouchY = event.y.toInt()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun loadUrl(url: String) {
        when {
            Config.HOME_URL_ALIAS == url -> {
                when (TVBro.config.homePageMode) {
                    Config.HomePageMode.BLANK -> {
                        loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
                    }
                    Config.HomePageMode.CUSTOM, Config.HomePageMode.SEARCH_ENGINE -> {
                        try {
                            currentOriginalUrl = Uri.parse(TVBro.config.homePage)
                            super.loadUrl(TVBro.config.homePage)
                        } catch (e: Exception) {
                            Log.e(TAG, "LoadUrl error", e)
                            loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
                        }

                    }
                    Config.HomePageMode.HOME_PAGE -> {
                        currentOriginalUrl = Uri.parse(Config.HOME_PAGE_URL)
                        super.loadUrl(Config.HOME_PAGE_URL)
                        //val data = context.assets.open("pages/home/index.html").bufferedReader().use { it.readText() }
                        //loadDataWithBaseURL(Config.HOME_PAGE_URL, data, "text/html", "UTF-8", null)
                    }
                }

            }
            url.startsWith(INTERNAL_SCHEME) -> {
                val uri = Uri.parse(url)
                when (uri.authority) {
                    INTERNAL_SCHEME_WARNING_DOMAIN -> {
                        when (uri.getQueryParameter("type")) {
                            INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT -> {
                                val data = context.assets.open("pages/warning-certificate.html").bufferedReader().use { it.readText() }
                                loadDataWithBaseURL("file:///android_asset/", data, "text/html", "UTF-8", uri.getQueryParameter("url"))
                            }
                        }
                    }
                }
            }
            else -> {
                currentOriginalUrl = Uri.parse(url)
                super.loadUrl(url)
            }
        }
    }

    private fun getGenericJSInjects(): String {
        var injects = genericInjects
        if (injects == null) {
            injects =
                context.assets.open("generic_injects.js").bufferedReader().use { it.readText() }
            genericInjects = injects
        }
        return injects
    }

    fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        if (width == 0 || height == 0) return null
        var thumbnail = bitmap
        if (thumbnail == null) {
            try {
                thumbnail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (e: Throwable) {
                e.printStackTrace()
                LogUtils.recordException(e)
                try {
                    thumbnail = Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    e.printStackTrace()
                    LogUtils.recordException(e)
                }
            }
        }
        if (thumbnail == null) {
            return null
        }
        val canvas = Canvas(thumbnail)
        val scaleFactor = width / width.toFloat()
        canvas.scale(scaleFactor, scaleFactor)
        canvas.translate(-scrollX.toFloat() * scaleFactor, -scrollY.toFloat() * scaleFactor)
        super.draw(canvas)
        return thumbnail
    }

    fun hideCustomView() {
        webChromeClient_.onHideCustomView()
    }

    fun onFilePicked(data: Intent) {
        pickFileCallback?.apply {
            if (data.data != null) {
                val uris = arrayOf(data.data!!)
                onReceiveValue(uris)
            }
        }
    }

    fun onPermissionsResult(permissions: Array<String>, grantResults: IntArray, typeGeo: Boolean) {
        if (typeGeo) geoPermissionsCallback?.apply {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                this.invoke(geoPermissionOrigin, true, true)
            } else {
                this.invoke(geoPermissionOrigin, false, false)
            }
            geoPermissionsCallback = null
            geoPermissionOrigin = null


        } else webPermissionsRequest?.apply {
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
            requestedWebResourcesThatDoNotNeedToGrantAndroidPermissions?.apply {
                resources.addAll(this)
                requestedWebResourcesThatDoNotNeedToGrantAndroidPermissions = null
            }
            if (resources.isEmpty()) {
                this.deny()
            } else {
                this.grant(resources.toTypedArray())
            }
            webPermissionsRequest = null
        }
    }

    fun onUpdateAdblockSetting(adblockEnabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = adblockEnabled
        }
    }

    //trick to make play/pause media buttons work
    fun togglePlayback() {
        evaluateJavascript("tvBroTogglePlayback()", null)
    }
}
