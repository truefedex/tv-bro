package com.phlox.tvwebbrowser.player

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Rational
import com.phlox.tvwebbrowser.BuildConfig
import kotlin.math.roundToInt

/** Whether the built-in player can go into a picture-in-picture window, and the shape of that window */
object PictureInPictureSupport {
    /**
     * The widest and the tallest window: the platform takes up to 2.39:1 and fails the window outside, compared as
     * floats, so the limit stays a little inside
     */
    const val MAX_RATIO = 2.38f
    private const val PRECISION = 1000

    /**
     * The build has it (not the Google Play one), the device has picture-in-picture (Android 8 or newer, not all
     * TVs; Google TV before Android 13 has none) and the user did not turn it off for the app: else nothing offers it
     */
    fun isAvailable(context: Context): Boolean {
        if (!BuildConfig.PICTURE_IN_PICTURE || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return true
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
            }
        } catch (e: Exception) {
            AppOpsManager.MODE_ALLOWED
        }
        return mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
    }

    /** The shape of the window for a picture of [ratio] (width to height): 16:9 while unknown, within the limits */
    fun aspectRatio(ratio: Float?): Rational {
        if (ratio == null || !ratio.isFinite() || ratio <= 0f) return Rational(16, 9)
        val clamped = ratio.coerceIn(1f / MAX_RATIO, MAX_RATIO)
        return if (clamped >= 1f) Rational((clamped * PRECISION).roundToInt(), PRECISION)
        else Rational(PRECISION, (PRECISION / clamped).roundToInt())
    }
}
