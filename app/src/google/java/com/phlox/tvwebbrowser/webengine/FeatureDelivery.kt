package com.phlox.tvwebbrowser.webengine

import android.app.Activity
import android.content.Context
import com.google.android.play.core.splitcompat.SplitCompat

/**
 * Google Play builds get the gecko module through on-demand feature delivery.
 */
object FeatureDelivery {
    /**
     * Makes already downloaded feature modules available. Call from Application.attachBaseContext.
     */
    fun install(context: Context) {
        SplitCompat.install(context)
    }

    /**
     * Gives the activity access to resources of feature modules installed while the app runs.
     */
    fun installActivity(activity: Activity) {
        SplitCompat.installActivity(activity)
    }

    fun createGeckoInstaller(): WebEngineInstaller = GeckoFeatureInstaller()
}
