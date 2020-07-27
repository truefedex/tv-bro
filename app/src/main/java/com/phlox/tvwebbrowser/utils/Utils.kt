package com.phlox.tvwebbrowser.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.io.IOException

import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Calendar


object Utils {

    /**
     * Returns the screen/display size
     */
    fun getDisplaySize(context: Context): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val size = Point()
        display.getSize(size)
        return size
    }

    /**
     * Shows a (long) toast
     */
    fun showToast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * Shows a (long) toast.
     */
    fun showToast(context: Context, resourceId: Int) {
        Toast.makeText(context, context.getString(resourceId), Toast.LENGTH_LONG).show()
    }

    fun D2P(ctx: Context, dp: Float): Float {
        val density = ctx.resources.displayMetrics.density
        return dp * density
    }

    /**
     * Formats time in milliseconds to hh:mm:ss string format.
     */
    fun formatMillis(millis: Int): String {
        var millis = millis
        var result = ""
        val hr = millis / 3600000
        millis %= 3600000
        val min = millis / 60000
        millis %= 60000
        val sec = millis / 1000
        if (hr > 0) {
            result += hr.toString() + ":"
        }
        if (min >= 0) {
            if (min > 9) {
                result += min.toString() + ":"
            } else {
                result += "0$min:"
            }
        }
        if (sec > 9) {
            result += sec
        } else {
            result += "0$sec"
        }
        return result
    }

    fun MD5_Hash(bytes: ByteArray): String? {
        try {
            val m = MessageDigest.getInstance("MD5")
            m.update(bytes, 0, bytes.size)
            return BigInteger(1, m.digest()).toString(16)
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }

        return null
    }

    fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting
    }

    fun isSameDate(date: Long, other: Long): Boolean {
        if (other == -1L) return false
        val c1 = Calendar.getInstance()
        c1.timeInMillis = date
        val c2 = Calendar.getInstance()
        c2.timeInMillis = other
        return c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR) && c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
    }

    @Throws(IOException::class)
    fun createTempFile(context: Context, fileName: String): File {
        val externalCacheDir = context.externalCacheDir
        val internalCacheDir = context.cacheDir
        val cacheDir: File
        if (externalCacheDir == null && internalCacheDir == null) {
            throw IOException("No cache directory available")
        }
        if (externalCacheDir == null) {
            cacheDir = internalCacheDir
        } else if (internalCacheDir == null) {
            cacheDir = externalCacheDir
        } else {
            cacheDir = if (externalCacheDir.freeSpace > internalCacheDir.freeSpace)
                externalCacheDir
            else
                internalCacheDir
        }
        return File(cacheDir, fileName)
    }

    fun isTV(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun isFireTV(context: Context): Boolean {
        return context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
