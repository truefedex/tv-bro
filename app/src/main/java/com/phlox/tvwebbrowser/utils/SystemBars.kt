package com.phlox.tvwebbrowser.utils

import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides and shows the status and navigation bars (where the device has them) for fullscreen content.
 */
object SystemBars {
    /** Hides the bars; a swipe from the edge shows them for a while */
    fun hide(window: Window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun show(window: Window) {
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }
}
