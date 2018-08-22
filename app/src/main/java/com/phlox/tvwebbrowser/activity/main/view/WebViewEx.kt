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
import com.phlox.tvwebbrowser.activity.main.MainActivity

/**
 * Created by fedex on 12.08.16.
 */
class WebViewEx : WebView {
    companion object {
        var defaultUAString: String? = null
    }

    private var needThumbnail: Size? = null
    private var listener: Listener? = null

    private var actionsMenu: PopupMenu? = null
    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0
    var uaString: String? = null

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

        val browserSettings = settings
        browserSettings.javaScriptCanOpenWindowsAutomatically = true
        if (defaultUAString == null) {
            defaultUAString = "TV Bro/1.0 " + browserSettings.userAgentString.replace("Mobile Safari", "Safari")
        }
        val prefs = context.getSharedPreferences(MainActivity.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        uaString = prefs.getString(MainActivity.USER_AGENT_PREF_KEY, "")
        if ("" == uaString) {
            uaString = defaultUAString
        }
        browserSettings.userAgentString = uaString
        browserSettings.pluginState = WebSettings.PluginState.ON_DEMAND
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            browserSettings.safeBrowsingEnabled = false
        }
        browserSettings.javaScriptEnabled = true
        browserSettings.databaseEnabled = true
        browserSettings.useWideViewPort = true
        browserSettings.setSupportZoom(true)
        browserSettings.builtInZoomControls = false
        browserSettings.displayZoomControls = false
        browserSettings.saveFormData = true
        browserSettings.setSupportZoom(true)
        browserSettings.domStorageEnabled = true
        browserSettings.allowContentAccess = false
        browserSettings.setAppCachePath(context.cacheDir.absolutePath)
        browserSettings.setAppCacheEnabled(true)
        browserSettings.cacheMode = WebSettings.LOAD_DEFAULT
        browserSettings.mediaPlaybackRequiresUserGesture = false
        browserSettings.setGeolocationEnabled(true)
        browserSettings.javaScriptCanOpenWindowsAutomatically = false
        browserSettings.setSupportMultipleWindows(false)

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
