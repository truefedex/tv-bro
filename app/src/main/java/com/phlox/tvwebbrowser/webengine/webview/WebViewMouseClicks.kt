package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.util.Log
import androidx.webkit.WebViewCompat
import com.phlox.tvwebbrowser.widgets.cursor.MouseActionButton
import com.phlox.tvwebbrowser.widgets.cursor.MouseClicks

/**
 * How the mouse of the cursor clicks in WebView. Chromium takes the button of `mousedown` and
 * `mouseup` from `actionButton`, which `MotionEvent.obtain` leaves 0; before 137 it drops such a
 * press (from 137 it falls back to `buttonState`, crbug.com/409639106). So older versions get the
 * button in `actionButton` where the hidden setter works, else the clicks as a finger. Newer ones
 * keep `actionButton` 0: with it set they would count double clicks of the OK key (`dblclick`,
 * a selected word, fullscreen on YouTube).
 */
object WebViewMouseClicks {
    private const val TAG = "WebViewMouseClicks"
    /** The first major version of WebView that takes the button from `buttonState` */
    const val BUTTON_STATE_VERSION = 137

    private var chosen: MouseClicks? = null

    /** Call after the engine is initialized: asking for the package of WebView may load it */
    fun get(context: Context): MouseClicks {
        chosen?.let { return it }
        val clicks = choose(MouseActionButton.canSet) { webViewMajorVersion(context) }
        Log.i(TAG, "mouse clicks: $clicks")
        chosen = clicks
        return clicks
    }

    fun choose(canSetActionButton: Boolean, webViewMajorVersion: () -> Int?): MouseClicks {
        val version = webViewMajorVersion()
        return when {
            version != null && version >= BUTTON_STATE_VERSION -> MouseClicks.BUTTON_STATE
            // every version takes these, an unknown one too
            canSetActionButton -> MouseClicks.ACTION_BUTTON
            else -> MouseClicks.TOUCH
        }
    }

    private fun webViewMajorVersion(context: Context): Int? = try {
        WebViewCompat.getCurrentWebViewPackage(context)?.versionName?.substringBefore('.')?.toIntOrNull()
    } catch (e: Throwable) {
        Log.w(TAG, "no WebView package", e)
        null
    }
}
