package com.phlox.tvwebbrowser.activity.main.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.PopupMenu
import com.phlox.tvwebbrowser.R

/**
 * Created by fedex on 12.08.16.
 */
class WebViewEx : WebView {
    companion object {
        const val HOME_URL = "about:blank"
    }

    private var needThumbnail: Size? = null
    private var listener: Listener? = null

    private var actionsMenu: PopupMenu? = null
    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0

    interface Listener {
        fun onThumbnailReady(thumbnail: Bitmap)
        fun onOpenInNewTabRequested(s: String)
        fun onDownloadRequested(url: String)
        fun onWantZoomMode()
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
            builtInZoomControls = false
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
                    listener?.onWantZoomMode()
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

    fun setNeedThumbnail(needThumbnail: Size?) {
        this.needThumbnail = needThumbnail
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

    override fun onDraw(canvas: Canvas) {
        if (needThumbnail != null && listener != null) {
            renderThumbnail()
        }

        super.onDraw(canvas)
    }

    override fun loadUrl(url: String?) {
        if (HOME_URL == url) {
            val data = context.assets.open("pages/new-tab.html").bufferedReader().use { it.readText() }
            loadDataWithBaseURL(null, data, "text/html", "UTF-8", null)
        } else {
            super.loadUrl(url)
        }
    }

    private fun renderThumbnail() {
        val thumbnail = Bitmap.createBitmap(needThumbnail!!.width, needThumbnail!!.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(thumbnail)
        val scaleFactor = needThumbnail!!.width / width.toFloat()
        needThumbnail = null
        canvas.scale(scaleFactor, scaleFactor)
        super.draw(canvas)
        listener!!.onThumbnailReady(thumbnail)
    }
}
