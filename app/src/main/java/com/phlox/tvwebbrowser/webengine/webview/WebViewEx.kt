package com.phlox.tvwebbrowser.webengine.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaDrm
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.Config.PlayerKind
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.player.MediaDiscoveryNotifier
import com.phlox.tvwebbrowser.player.MediaMetadataCache
import com.phlox.tvwebbrowser.player.MediaMetadataPrefetcher
import com.phlox.tvwebbrowser.player.MediaRequestCatalog
import com.phlox.tvwebbrowser.player.MediaRequestContext
import com.phlox.tvwebbrowser.player.MediaUrlIdentity
import com.phlox.tvwebbrowser.player.NativeVideoActivity
import com.phlox.tvwebbrowser.player.PlaybackFailure
import com.phlox.tvwebbrowser.player.PlayerLauncher
import com.phlox.tvwebbrowser.utils.DPADNavigationEventsAdapter
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.MediaCandidate
import java.net.URLEncoder
import java.util.UUID


/**
 * Copyright (c) 2016 Fedir Tsapana.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
open class WebViewEx(context: Context, val callback: Callback, val jsInterface: AndroidJSInterface) : WebView(context) {
    companion object {
        val TAG = WebViewEx::class.java.simpleName
        const val WEB_VIEW_TAG = "TV Bro WebView"
        const val INTERNAL_SCHEME = "internal://"
        const val INTERNAL_SCHEME_WARNING_DOMAIN = "warning"
        const val INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT = "certificate"
        const val OVERLAY_ZAPPER_ASSET = "overlay_zapper.js"
        const val MAX_DETECTED_MEDIA = 30
        //coming back from an external player sooner than this means it likely failed
        const val EXTERNAL_PLAYER_FAIL_WINDOW = 15000L
        val WIDEVINE_UUID = UUID(-0x121074568629b532L,-0x5c37d8232ae2de13L)
    }

    /** A media stream found on the page, together with the request headers it was seen with. */
    data class CastCandidate(val url: String, val headers: Map<String, String>,
        val kind: MediaRequestCatalog.Kind)

    private var virtualCursorMode: Boolean = true
    private var genericInjects: String? = null
    private var overlayZapperHandler: ScriptHandler? = null
    private var overlayZapperScript: String? = null
    private var overlayZapperFallback = false
    private var webChromeClient_: WebChromeClient
    private var fullscreenViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pickFileCallback: ValueCallback<Array<Uri>>? = null
    private var permRequestDialog: AlertDialog? = null
    private var webPermissionsRequest: PermissionRequest? = null
    private var requestedWebResourcesThatDoNotNeedToGrantAndroidPermissions: ArrayList<String>? = null
    private var geoPermissionOrigin: String? = null
    private var geoPermissionsCallback: GeolocationPermissions.Callback? = null
    var lastSSLError: SslError? = null
    var trustSsl: Boolean = false
    var currentOriginalUrl: Uri? = null
    //Media stream candidates detected at the network layer (m3u8/mpd/mp4...), with
    //the headers they were requested with. Insertion order is preserved, which
    //matters: see the sniffing code below.
    val detectedMedia = LinkedHashMap<String, Map<String, String>>()
    //Identity (see MediaUrlIdentity) -> URL, so a refreshed signed URL replaces its old entry.
    private val detectedMediaIdentities = HashMap<String, String>()
    //set when an external player was launched, to detect a quick bounce back
    private var externalPlayerStartedAt = 0L
    private var externalPlayerUrl: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val config = AppContext.provideConfig()

    interface Callback {
        fun getActivity(): Activity?
        fun onOpenInNewTabRequested(url: String)
        fun onDownloadRequested(url: String)
        fun onThumbnailError()
        fun onShowCustomView(view: View)
        fun onHideCustomView()
        fun onProgressChanged(newProgress: Int)
        fun onReceivedTitle(title: String)
        fun onShowFileChooser(intent: Intent): Boolean
        fun onReceivedIcon(icon: Bitmap)
        fun requestPermissions(array: Array<String>, geo: Boolean)
        fun shouldOverrideUrlLoading(url: String, isAutomaticRedirect: Boolean = false): Boolean
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
        fun onContextMenu(baseUrl: String?, href: String?, x: Int, y: Int)
    }

    init {
        with(settings) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = callback.isAdBlockingEnabled()
            }
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            domStorageEnabled = true
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = !config.allowAutoplayMedia
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setNeedInitialFocus(false)

            domStorageEnabled = true
            if (config.webEngineDebug) {
                setWebContentsDebuggingEnabled(true)
            }

            val allowDarkening = config.webviewUseAlgorithmicDarkeningWithDarkUiMode
            val uiNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    if (uiNightMode == Configuration.UI_MODE_NIGHT_YES && allowDarkening) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
                    } else {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
                    }
                }
            } else {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    if (uiNightMode == Configuration.UI_MODE_NIGHT_YES && allowDarkening) {
                        WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_ON)
                    } else {
                        WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_OFF)
                    }
                }
            }
        }

        setOnLongClickListener { v ->
            true
        }

        updateOverlayZapper(callback.isAdBlockingEnabled())

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
                fullscreenViewCallback = null
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
                return callback.shouldOverrideUrlLoading(request.url.toString(), request.isRedirect)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                Log.d(TAG, "shouldInterceptRequest url: ${request.url}")
                run {
                    //Collect media stream candidates. These are generic patterns,
                    //not per-site rules. Order is kept (LinkedHashMap) because with
                    //HLS the master playlist is usually requested first, so it ends
                    //up first in the list and is the best pick.
                    val u = request.url.toString()
                    if (isMediaCandidate(u) && detectedMedia.size < MAX_DETECTED_MEDIA) {
                        val identity = MediaUrlIdentity.key(u)
                        val previous = detectedMediaIdentities[identity]
                        when {
                            previous == null -> {
                                detectedMedia[u] = request.requestHeaders ?: emptyMap()
                                detectedMediaIdentities[identity] = u
                                MediaDiscoveryNotifier.notifyChanged()
                            }
                            previous != u -> {
                                //Same resource with rotated credentials (e.g. a new signature):
                                //swap in the freshest URL without disturbing the list order.
                                val headers = request.requestHeaders ?: emptyMap()
                                val rebuilt = LinkedHashMap<String, Map<String, String>>(detectedMedia.size)
                                for ((key, value) in detectedMedia) {
                                    if (key == previous) rebuilt[u] = headers else rebuilt[key] = value
                                }
                                detectedMedia.clear()
                                detectedMedia.putAll(rebuilt)
                                detectedMediaIdentities[identity] = u
                                MediaDiscoveryNotifier.notifyChanged()
                            }
                        }
                    }
                }
                val currentPageUrl = currentOriginalUrl

                if (currentPageUrl != null && currentPageUrl.toString().startsWith(Config.HOME_PAGE_URL,
                        ignoreCase = true)) {
                    HomePageHelper.shouldInterceptRequest(view, request)?.let {
                        return it
                    }
                    if (request.url.toString().startsWith(Config.HOME_PAGE_URL)) {
                        var relativePath = request.url.toString().substring(Config.HOME_PAGE_URL.length)
                        if (relativePath.isEmpty() || relativePath == "/") {
                            relativePath = "index.html"
                        }
                        val assetsPath = "pages/home/$relativePath"
                        val response = Utils.getWebResourceResponseFromAssets(view.context, assetsPath)
                        if (response != null) {
                            Log.d(TAG, "shouldInterceptRequest url: ${request.url} -> $assetsPath")
                            return response
                        } else {
                            Log.w(TAG, "shouldInterceptRequest url: ${request.url} -> not found in assets")
                        }
                        return response ?: super.shouldInterceptRequest(view, request)
                    }
                }

                if (!callback.isAdBlockingEnabled()) {
                    return super.shouldInterceptRequest(view, request)
                }

                val ad = currentPageUrl?.let { callback.isAd(request, it)} ?: false
                return if (ad) {
                    Log.d(TAG, "Blocked ads request: ${request.url}")
                    uiHandler.post { callback.onBlockedAd(request.url) }
                    val response = WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                    response.setStatusCodeAndReasonPhrase(403, "Blocked")
                    response
                } else super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted url: $url")
                currentOriginalUrl = url.toUri()
                detectedMedia.clear()
                detectedMediaIdentities.clear()
                callback.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished url: $url")
                callback.onPageFinished(url)
                evaluateJavascript(getGenericJSInjects(), null)
                injectOverlayZapperFallback()
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

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?
            ) {
                val userNameEdit = EditText(context).also {
                    it.hint = context.getString(com.phlox.tvwebbrowser.common.R.string.username)
                    it.isSingleLine = true
                }
                val passwordEdit = EditText(context).also {
                    it.hint = context.getString(com.phlox.tvwebbrowser.common.R.string.password)
                    it.isSingleLine = true
                    it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                val container = LinearLayout(context).also {
                    it.orientation = LinearLayout.VERTICAL
                    it.addView(userNameEdit)
                    it.addView(passwordEdit)
                }
                AlertDialog.Builder(context)
                    .setTitle(R.string.http_auth_title)
                    .setCancelable(false)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _: DialogInterface, _:Int ->
                        handler?.proceed(userNameEdit.text.toString(), passwordEdit.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _: DialogInterface, _:Int ->
                        handler?.cancel()
                    }
                    .show()
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

    override fun restoreState(inState: Bundle): WebBackForwardList? {
        val result = super.restoreState(inState)
        currentOriginalUrl = url?.toUri()
        return result
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (virtualCursorMode && DPADNavigationEventsAdapter.isNavigationGenericMotionSource(event.source))
            return false
        return super.dispatchGenericMotionEvent(event)
    }

    private fun showCertificateErrorPage(error: SslError) {
        callback.onPageCertificateError(error.url)
        lastSSLError = error
        val url = INTERNAL_SCHEME + INTERNAL_SCHEME_WARNING_DOMAIN +
                "?type=" + INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT +
                "&url=" + URLEncoder.encode(error.url, "UTF-8")
        loadUrl(url)
    }

    override fun loadUrl(url: String) {
        when {
            Config.HOME_URL_ALIAS == url -> {
                when (config.homePageMode) {
                    Config.HomePageMode.BLANK -> {
                        loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
                    }
                    Config.HomePageMode.CUSTOM, Config.HomePageMode.SEARCH_ENGINE -> {
                        try {
                            currentOriginalUrl = config.homePage.toUri()
                            super.loadUrl(config.homePage)
                        } catch (e: Exception) {
                            Log.e(TAG, "LoadUrl error", e)
                            loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
                        }

                    }
                    Config.HomePageMode.HOME_PAGE -> {
                        currentOriginalUrl = Config.HOME_PAGE_URL.toUri()
                        super.loadUrl(Config.HOME_PAGE_URL)
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
                thumbnail = createBitmap(width, height, Bitmap.Config.RGB_565)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        if (thumbnail == null) {
            return null
        }
        val canvas = Canvas(thumbnail)
        val scaleFactor = thumbnail.width / width.toFloat()
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
        updateOverlayZapper(adblockEnabled)
    }

    /**
     * Registers (or removes) the cosmetic ad-overlay zapper as a document-start
     * script running in every frame. It complements the network-level ad blocking
     * with runtime DOM cleanup of overlays/click hijackers that first-party
     * scripts build after load. A no-op if the WebView provider is too old.
     */
    private fun updateOverlayZapper(enabled: Boolean) {
        if (!enabled) {
            overlayZapperHandler?.remove()
            overlayZapperHandler = null
            overlayZapperFallback = false
            return
        }
        if (overlayZapperHandler != null || overlayZapperFallback) return
        val script = overlayZapperScript ?: try {
            context.assets.open(OVERLAY_ZAPPER_ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "overlay zapper asset not available", e)
            return
        }.also { overlayZapperScript = it }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // WebView older than M86 has no document-start API. Fall back to injecting
            // the zapper into the main frame once a page has loaded (see
            // injectOverlayZapperFallback); it then observes the DOM from there.
            Log.d(TAG, "overlay zapper: no document-start API, using load-time fallback")
            overlayZapperFallback = true
            return
        }
        try {
            overlayZapperHandler = WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
        } catch (e: Exception) {
            Log.w(TAG, "failed to register overlay zapper", e)
            overlayZapperFallback = true
        }
    }

    /** Runs the zapper in the main frame on old WebViews (no document-start API). */
    fun injectOverlayZapperFallback() {
        if (!overlayZapperFallback) return
        overlayZapperScript?.let { evaluateJavascript(it, null) }
    }

    fun setVirtualCursorMode(enabled: Boolean) {
        this.virtualCursorMode = enabled
    }

    //region media casting

    /** Generic patterns that mark a URL as a possible media stream, not per-site rules. */
    private fun isMediaCandidate(u: String): Boolean {
        val path = u.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            isProgressiveFile(u) || isMaskedManifest(path) || isUrlManifest(path) ||
            u.contains("action=redirect") || path.contains("/api/er/get") ||
            queryNamesManifest(u)
    }

    /**
     * Some players fetch the manifest from an extensionless gateway with the real file
     * name tucked in the query, e.g. ".../stream.php?path=ab12/index.m3u8&sig=...".
     */
    private fun queryNamesManifest(u: String): Boolean {
        if (!u.contains('?')) return false
        val query = Uri.decode(u.substringAfter('?')).lowercase()
        return query.contains(".m3u8") || query.contains(".mpd")
    }

    /** Some sites disguise the HLS manifest behind a harmless extension. */
    private fun isMaskedManifest(path: String): Boolean =
        (path.endsWith(".txt") || path.endsWith(".json")) &&
            (path.contains("/hls/") || path.contains("master") || path.contains("playlist") ||
                path.contains("manifest") || path.contains("sublist") || path.contains("chunklist"))

    /** Some player services serve the manifest with no extension at all, behind .../q/<n>. */
    private fun isUrlManifest(path: String): Boolean {
        val qIdx = path.lastIndexOf("/q/")
        return qIdx >= 0 && path.substring(qIdx + 3).toIntOrNull() != null
    }

    private fun isProgressiveFile(u: String): Boolean {
        val p = u.substringBefore('?').lowercase()
        return p.endsWith(".mp4") || p.endsWith(".m4v") || p.endsWith(".webm") ||
            p.endsWith(".mkv") || p.endsWith(".mov")
    }

    private fun inferCastKind(u: String): MediaRequestCatalog.Kind {
        val path = u.substringBefore('?').lowercase()
        return when {
            path.endsWith(".mpd") -> MediaRequestCatalog.Kind.DASH
            path.endsWith(".m3u8") || isMaskedManifest(path) || isUrlManifest(path) -> MediaRequestCatalog.Kind.HLS
            queryNamesManifest(u) ->
                if (Uri.decode(u.substringAfter('?')).lowercase().contains(".mpd"))
                    MediaRequestCatalog.Kind.DASH else MediaRequestCatalog.Kind.HLS
            else -> MediaRequestCatalog.Kind.PROGRESSIVE
        }
    }

    /**
     * Orders the candidates by how likely they are to be the real stream. No
     * per-site rules: manifests first (HLS/DASH - the master playlist normally
     * loads before anything else), single files last.
     */
    private fun orderedCandidates(domUrl: String?): List<CastCandidate> {
        val urls = LinkedHashSet<String>()
        domUrl?.takeIf { it.isNotEmpty() && !it.startsWith("blob:") }?.let { urls.add(it) }
        urls.addAll(detectedMedia.keys)
        //sortedBy is stable, so the detection order survives within each group
        return urls.map { CastCandidate(it, detectedMedia[it] ?: emptyMap(), inferCastKind(it)) }
            .sortedBy { if (isProgressiveFile(it.url)) 1 else 0 }
    }

    /**
     * Public snapshot of the streams detected on the page, for the toolbar media list.
     * The page URL itself is only treated as a candidate when it actually points at a media
     * file: otherwise every ordinary page (e.g. the home page) would list itself as
     * "PROGRESSIVE".
     */
    fun mediaCandidates(): List<MediaCandidate> =
        orderedCandidates(currentOriginalUrl?.toString()?.takeIf(::isMediaCandidate))
            .map { MediaCandidate(it.url, it.kind.toCandidateKind()) }

    /** Warms the metadata cache for manifests on the page; plain files carry no parseable metadata. */
    fun prefetchMediaMetadata() {
        val page = currentOriginalUrl?.toString() ?: return
        val userAgent = settings.userAgentString ?: ""
        detectedMedia.keys.forEach { url ->
            val kind = inferCastKind(url)
            if (kind != MediaRequestCatalog.Kind.PROGRESSIVE && MediaMetadataCache.get(url) == null) {
                val request = MediaRequestCatalog.Request(
                    url = url, page = page, frame = page, privateMode = false, kind = kind,
                    headers = detectedMedia[url] ?: emptyMap())
                MediaMetadataPrefetcher.prefetch(url, kind, MediaRequestContext(request, listOf(request), userAgent))
            }
        }
    }

    /** Plays one detected stream in the configured player, bypassing the page controls. */
    fun playMediaCandidate(url: String) {
        playCandidate(CastCandidate(url, detectedMedia[url] ?: emptyMap(), inferCastKind(url)),
            config.playerKind, 0L)
    }

    private fun MediaRequestCatalog.Kind.toCandidateKind(): MediaCandidate.Kind = when (this) {
        MediaRequestCatalog.Kind.HLS -> MediaCandidate.Kind.HLS
        MediaRequestCatalog.Kind.DASH -> MediaCandidate.Kind.DASH
        MediaRequestCatalog.Kind.PROGRESSIVE -> MediaCandidate.Kind.PROGRESSIVE
    }

    /**
     * Hands one stream to the player selected in the settings. There is no automatic fallback:
     * a stream that does not play is the user's cue to try a different one from the stream list.
     */
    private fun playCandidate(candidate: CastCandidate, kind: PlayerKind, startMs: Long) {
        when (kind) {
            PlayerKind.BUILT_IN -> playInInternalPlayer(candidate, startMs)
            PlayerKind.SYSTEM -> playInSystemPlayer(candidate)
            PlayerKind.EXTERNAL -> playInExternalPlayer(candidate)
        }
    }

    /** Opens the stream in TV Bro's built-in (ExoPlayer) player. */
    fun playInInternalPlayer(candidate: CastCandidate) = playInInternalPlayer(candidate, 0L)

    /** Opens the stream in TV Bro's built-in (ExoPlayer) player, resuming at [startMs]. */
    fun playInInternalPlayer(candidate: CastCandidate, startMs: Long) {
        val activity = callback.getActivity() ?: return
        val page = currentOriginalUrl?.toString() ?: return
        val ua = settings.userAgentString.orEmpty()
        val source = MediaRequestCatalog.Request(
            url = candidate.url, page = page, frame = page, privateMode = false,
            kind = candidate.kind, headers = candidate.headers, tabId = -1)
        val observations = (listOf(source) + detectedMedia.map { (u, h) ->
            MediaRequestCatalog.Request(u, page, page, false, inferCastKind(u), h, tabId = -1)
        }).distinctBy { it.url }
        val requestContext = MediaRequestContext(source, observations, ua)
        PlaybackFailure.last = null
        try {
            NativeVideoActivity.launch(activity,
                NativeVideoActivity.PlaybackRequest(source, requestContext, startMs, 1f)) { result ->
                if (result.action == NativeVideoActivity.ReturnAction.FAILED) {
                    Toast.makeText(context, R.string.native_playback_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, R.string.cast_no_media, Toast.LENGTH_LONG).show()
        }
    }

    /** Opens the stream in the platform MediaPlayer based fallback player. */
    fun playInSystemPlayer(candidate: CastCandidate) {
        val activity = callback.getActivity() ?: return
        val cookie = try {
            CookieManager.getInstance().getCookie(candidate.url)
        } catch (e: Exception) { null }
        PlayerLauncher.openSystem(activity, candidate.url, settings.userAgentString,
            currentOriginalUrl?.toString(), cookie)
    }

    /** Hands the stream over to an external player (VLC, MX Player...). */
    fun playInExternalPlayer(candidate: CastCandidate) {
        val activity = callback.getActivity() ?: return
        val cookie = try {
            CookieManager.getInstance().getCookie(candidate.url)
        } catch (e: Exception) { null }
        PlayerLauncher.openExternal(activity, candidate.url, settings.userAgentString,
            currentOriginalUrl?.toString(), cookie) {
            //There is no callback telling us whether the other app managed to play
            //the stream. Streams that need a Referer on every request keep buffering
            //there forever, and the user comes straight back. So if we are resumed
            //again after a short while, offer the built-in player, which can play them.
            externalPlayerStartedAt = System.currentTimeMillis()
            externalPlayerUrl = candidate.url
        }
    }

    override fun onResume() {
        super.onResume()
        //the system player closed itself because playback failed; there is no fallback
        if (PlaybackFailure.last != null) {
            PlaybackFailure.last = null
            externalPlayerStartedAt = 0L
            externalPlayerUrl = null
            return
        }
        val startedAt = externalPlayerStartedAt
        val url = externalPlayerUrl
        externalPlayerStartedAt = 0L
        externalPlayerUrl = null
        if (startedAt == 0L || url == null) return
        if (System.currentTimeMillis() - startedAt > EXTERNAL_PLAYER_FAIL_WINDOW) return//watched it
        val activity = callback.getActivity() ?: return
        AlertDialog.Builder(activity)
            .setTitle(R.string.external_playback_failed_title)
            .setMessage(R.string.external_playback_failed_message)
            .setPositiveButton(R.string.cast_open_builtin) { _, _ ->
                playInInternalPlayer(CastCandidate(url, detectedMedia[url] ?: emptyMap(), inferCastKind(url)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    //endregion
}
