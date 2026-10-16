package com.phlox.tvwebbrowser.activity.main

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.phlox.tvwebbrowser.Config

/** The look of the browser screen */
enum class UiMode { TV, PHONE }

/**
 * Picks the look of the browser screen: the Interface setting, or on Auto what the device is.
 */
object UiModeSelector {
    fun select(context: Context, setting: Config.InterfaceMode): UiMode = when (setting) {
        Config.InterfaceMode.TV -> UiMode.TV
        Config.InterfaceMode.PHONE -> UiMode.PHONE
        Config.InterfaceMode.AUTO -> detect(context)
    }

    fun detect(context: Context): UiMode {
        val configuration = context.resources.configuration
        return detect(
            uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK,
            hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            touchscreen = configuration.touchscreen,
            smallestScreenWidthDp = configuration.smallestScreenWidthDp
        )
    }

    /**
     * The phone UI is for touch phones: not a TV, a touch screen that is really there (the configuration
     * comes from the input devices; TV boxes with tablet firmware declare the touchscreen feature too),
     * and a small screen (no tablets for now). The width alone does not tell a phone from a TV:
     * a 1080p TV at xhdpi is 540dp wide.
     */
    fun detect(uiModeType: Int, hasLeanback: Boolean, touchscreen: Int, smallestScreenWidthDp: Int): UiMode {
        val phone = uiModeType != Configuration.UI_MODE_TYPE_TELEVISION &&
                !hasLeanback &&
                touchscreen == Configuration.TOUCHSCREEN_FINGER &&
                smallestScreenWidthDp < PHONE_MAX_SMALLEST_WIDTH_DP
        return if (phone) UiMode.PHONE else UiMode.TV
    }

    private const val PHONE_MAX_SMALLEST_WIDTH_DP = 600
}
