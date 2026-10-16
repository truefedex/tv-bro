package com.phlox.tvwebbrowser.utils

import android.content.res.Configuration
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The status and navigation bars (where the device has them): their icons, hiding them for fullscreen content.
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

    /** Dark icons over the light theme, light ones over the dark theme (the bars are drawn over the app) */
    fun matchIconsToTheme(window: Window) {
        val night = (window.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }
}
