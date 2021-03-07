package com.phlox.tvwebbrowser.activity.main.view

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.utils.LogUtils
import java.net.URLEncoder

/**
 * Copyright (c) 2016 Fedir Tsapana.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewEx(val callback: Callback, context: Context) : WebView(context) {
    companion object {
        val TAG = WebViewEx::class.java.simpleName
        const val HOME_URL = "about:blank"
        const val INTERNAL_SCHEME = "internal://"
        const val INTERNAL_SCHEME_WARNING_DOMAIN = "warning"
        const val INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT = "certificate"
    }

    private var webChromeClient_: WebChromeClient
    private var fullscreenViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pickFileCallback: ValueCallback<Array<Uri>>? = null
    private var actionsMenu: PopupMenu? = null
    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0
    private var permRequestDialog: AlertDialog? = null
    private var webPermissionsRequest: PermissionRequest? = null
    private var reuestedResourcesForAlreadyGrantedPermissions: ArrayList<String>? = null
    private var geoPermissionOrigin: String? = null
    private var geoPermissionsCallback: GeolocationPermissions.Callback? = null
    var lastSSLError: SslError? = null
    var trustSsl: Boolean = false
    private var currentOriginalUrl: Uri? = null

    interface Callback {
        fun getActivity(): Activity
        fun onOpenInNewTabRequested(s: String)
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
        fun isAd(request: WebResourceRequest, baseUri: Uri): Boolean
        fun onBlockedAdsCountChanged(blockedAds: Int)
    }

    init {
        with(settings) {
            javaScriptCanOpenWindowsAutomatically = true
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
            setAppCachePath(context.cacheDir.absolutePath)
            setAppCacheEnabled(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setNeedInitialFocus(false)
        }

        /*scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
        isScrollbarFadingEnabled = false*/

        setOnLongClickListener {
            evaluateJavascript(Scripts.LONG_PRESS_SCRIPT) { s ->
                if (s != null && "null" != s) {
                    suggestActionsForLink(s)
                } else {
                    callback?.onLongTap()
                }
            }
            true
        }

        webChromeClient_ = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                return super.onJsAlert(view, url, message, result)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                this@WebViewEx.callback?.onShowCustomView(view)
                fullscreenViewCallback = callback
            }

            override fun onHideCustomView() {
                callback?.onHideCustomView()
                fullscreenViewCallback?.onCustomViewHidden()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                callback?.onProgressChanged(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                callback?.onReceivedTitle(title)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val activity = callback?.getActivity() ?: return
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

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val neededPermissions = ArrayList<String>()
                                reuestedResourcesForAlreadyGrantedPermissions = ArrayList()
                                for (resource in webPermissionsRequest.resources) {
                                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE == resource) {
                                        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
                                        } else {
                                            reuestedResourcesForAlreadyGrantedPermissions!!.add(resource)
                                        }
                                    } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE == resource) {
                                        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                            neededPermissions.add(Manifest.permission.CAMERA)
                                        } else {
                                            reuestedResourcesForAlreadyGrantedPermissions!!.add(resource)
                                        }
                                    }
                                }

                                if (neededPermissions.isNotEmpty()) {
                                    callback?.requestPermissions(neededPermissions.toTypedArray(), false)
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
                permRequestDialog?.apply {
                    dismiss()
                    permRequestDialog = null
                }
                webPermissionsRequest = null
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val activity = this@WebViewEx.callback?.getActivity() ?: return
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                    ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                this@WebViewEx.callback?.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), true)
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

                val result = this@WebViewEx.callback?.onShowFileChooser(fileChooserParams.createIntent()) ?: false
                if (!result) {
                    pickFileCallback = null
                }
                return result
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap) {
                callback?.onReceivedIcon(icon)
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

        webViewClient = object : WebViewClient() {
            private var blockedAds = 0

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                Log.d(TAG, "shouldOverrideUrlLoading url: ${request.url}")
                return callback?.shouldOverrideUrlLoading(request.url.toString()) ?: false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                Log.d(TAG, "shouldInterceptRequest url: ${request.url}")

                if (callback?.isAdBlockingEnabled() != true) {
                    return super.shouldInterceptRequest(view, request)
                }

                val ad = currentOriginalUrl?.let { callback?.isAd(request, it)} ?: false
                return if (ad) {
                    Log.d(TAG, "Blocked ads request: ${request.url}")
                    blockedAds++
                    handler.post { callback?.onBlockedAdsCountChanged(blockedAds) }
                    WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                } else super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted url: $url")
                currentOriginalUrl = Uri.parse(url)
                callback?.onPageStarted(url)
                blockedAds = 0
                callback?.onBlockedAdsCountChanged(blockedAds)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished url: $url")
                callback?.onPageFinished(url)
            }

            override fun onLoadResource(view: WebView, url: String) {
                super.onLoadResource(view, url)
                Log.d(TAG, "onLoadResource url: $url")
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
                if (error.url == currentOriginalUrl.toString()) {//skip ssl errors during loading non-page resources (Chrome did like this too)
                    showCertificateErrorPage(error)
                }
            }
        }

        webChromeClient = webChromeClient_
    }

    private fun showCertificateErrorPage(error: SslError) {
        callback?.onPageCertificateError(error.url)
        lastSSLError = error
        val url = INTERNAL_SCHEME + INTERNAL_SCHEME_WARNING_DOMAIN +
                "?type=" + INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT +
                "&url=" + URLEncoder.encode(error.url, "UTF-8")
        loadUrl(url)
    }

    private fun suggestActionsForLink(href: String) {
        var s = href
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        val url = s.toLowerCase()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            val anchor = View(context)
            val parent = parent as FrameLayout
            val lp = FrameLayout.LayoutParams(1, 1)
            lp.setMargins(lastTouchX, lastTouchY, 0, 0)
            parent.addView(anchor, lp)
            actionsMenu = PopupMenu(context, anchor, Gravity.BOTTOM)
            val miNewTab = actionsMenu!!.menu.add(R.string.open_in_new_tab)
            actionsMenu!!.menu.add(R.string.download)
            actionsMenu!!.setOnMenuItemClickListener { menuItem ->
                if (menuItem === miNewTab) {
                    callback!!.onOpenInNewTabRequested(url)
                } else {
                    callback!!.onDownloadRequested(url)
                }
                true
            }

            actionsMenu!!.setOnDismissListener {
                parent.removeView(anchor)
                actionsMenu = null
            }
            actionsMenu!!.show()
        }
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
            HOME_URL == url -> {
                val data = context.assets.open("pages/new-tab.html").bufferedReader().use { it.readText() }
                loadDataWithBaseURL("file:///android_asset/", data, "text/html", "UTF-8", null)
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
                super.loadUrl(url)
            }
        }
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

    fun onHideCustomView() {
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
            reuestedResourcesForAlreadyGrantedPermissions?.apply {
                resources.addAll(this)
                reuestedResourcesForAlreadyGrantedPermissions = null
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
}
