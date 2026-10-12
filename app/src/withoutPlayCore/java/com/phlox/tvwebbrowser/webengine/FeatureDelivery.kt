package com.phlox.tvwebbrowser.webengine

import android.app.Activity
import android.content.Context

/**
 * Builds that are not distributed through Google Play (generic, foss) have no on-demand
 * feature delivery: the gecko module is either fused into the APK or absent.
 */
object FeatureDelivery {
    fun install(context: Context) {}

    fun installActivity(activity: Activity) {}

    fun createGeckoInstaller(): WebEngineInstaller? = null
}
