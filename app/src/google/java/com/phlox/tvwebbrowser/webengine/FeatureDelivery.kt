package com.phlox.tvwebbrowser.webengine

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.ktx.requestDeferredUninstall
import com.google.android.play.core.ktx.requestSessionStates
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory

/**
 * Google Play builds get the gecko module through on-demand feature delivery.
 */
object FeatureDelivery {
    private const val TAG = "FeatureDelivery"
    const val GECKO_MODULE_NAME = "gecko"

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

    /**
     * Whether the gecko module is installed and Google Play can remove it. Play can't manage
     * modules on some device classes (Android TV), and there it rejects any request.
     */
    suspend fun canUninstallGecko(context: Context): Boolean {
        val manager = SplitInstallManagerFactory.create(context)
        if (!manager.installedModules.contains(GECKO_MODULE_NAME)) return false
        return try {
            manager.requestSessionStates()
            true
        } catch (e: SplitInstallException) {
            Log.i(TAG, "Split install API is not available: ${e.errorCode}")
            false
        }
    }

    /**
     * Asks Google Play to remove the gecko module. Play does it later, in the background.
     * @return false if the request failed
     */
    suspend fun uninstallGecko(context: Context): Boolean {
        return try {
            SplitInstallManagerFactory.create(context).requestDeferredUninstall(listOf(GECKO_MODULE_NAME))
            true
        } catch (e: SplitInstallException) {
            Log.e(TAG, "Failed to request gecko module uninstall", e)
            false
        }
    }
}
