package com.phlox.tvwebbrowser.activity.main.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.PopupMenu
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.utils.LogUtils

/**
 * Created by fedex on 12.08.16.
 */
class WebViewEx : WebView {
    companion object {
        const val HOME_URL = "about:blank"
        const val INTERNAL_SCHEME = "internal://"
        const val INTERNAL_SCHEME_WARNING_DOMAIN = "warning"
        const val INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT = "certificate"
    }

    private var listener: Listener? = null
    private var actionsMenu: PopupMenu? = null
    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0

    interface Listener {
        fun onOpenInNewTabRequested(s: String)
        fun onDownloadRequested(url: String)
        fun onLongTap()
        fun onThumbnailError()
    }

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun init() {
        if (isInEditMode) {
            return
        }

        with(settings) {
            javaScriptCanOpenWindowsAutomatically = true
            pluginState = WebSettings.PluginState.ON_DEMAND
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
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
                    listener?.onLongTap()
                }
            }
            true
        }
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
                    listener!!.onOpenInNewTabRequested(url)
                } else {
                    listener!!.onDownloadRequested(url)
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

    fun setListener(listener: Listener) {
        this.listener = listener
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
}
