package com.phlox.tvwebbrowser.utils

import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi

/**
 * A back callback of the window above all the others ([OnBackInvokedDispatcher.PRIORITY_OVERLAY]) while it is
 * registered; it gives each Back to [onBack]. Where the window gives Back to its [OnBackInvokedCallback]s (apps
 * that target Android 16, on Android 16+), WebView registers one of its own for its fullscreen view, above the
 * one of the activity, and leaves fullscreen before the app sees the press. The browser activity registers this
 * one while a page is fullscreen, so its back callbacks decide there too (the built-in player over the page, the
 * cursor of the video mode). Where the window does not give Back to these callbacks, registering does nothing.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class OverlayBackInvokedCallback(private val dispatcher: OnBackInvokedDispatcher, onBack: () -> Unit) {
    private val callback = OnBackInvokedCallback { onBack() }

    var isRegistered = false
        private set

    /** A call that changes nothing is ignored: the engine may report the same fullscreen state twice */
    fun setRegistered(registered: Boolean) {
        if (registered == isRegistered) return
        if (registered) {
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        } else {
            dispatcher.unregisterOnBackInvokedCallback(callback)
        }
        isRegistered = registered
    }
}
