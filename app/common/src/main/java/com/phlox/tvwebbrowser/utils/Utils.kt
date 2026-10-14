package com.phlox.tvwebbrowser.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebResourceResponse
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.URLConnection
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import androidx.core.net.toUri


object Utils {

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

    fun hashMD5(bytes: ByteArray): String? {
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
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isSameDate(date: Long, other: Long): Boolean {
        if (other == -1L) return false
        val c1 = Calendar.getInstance()
        c1.timeInMillis = date
        val c2 = Calendar.getInstance()
        c2.timeInMillis = other
        return c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR) && c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
    }

    fun createTempFile(context: Context, fileName: String): File {
        val externalCacheDir = context.externalCacheDir
        val internalCacheDir = context.cacheDir
        val cacheDir: File
        if (externalCacheDir == null && internalCacheDir == null) {
            throw Exception("No cache directory available")
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

    fun isInstalledByAPK(context: Context): Boolean {
        val installer = context.packageManager.getInstallerPackageName(context.packageName)
        return installer == null || "com.google.android.packageinstaller".equals(installer)
    }

    fun bundleToBytes(bundle: Bundle): ByteArray? {
        val parcel = Parcel.obtain()
        parcel.writeBundle(bundle)
        val bytes = parcel.marshall()
        parcel.recycle()
        return bytes
    }

    fun bytesToBundle(bytes: ByteArray): Bundle? {
        return try {
            val parcel = Parcel.obtain()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val bundle = parcel.readBundle(Utils::class.java.classLoader)
            parcel.recycle()
            bundle!!
        } catch (e: Exception) {
            null
        }
    }

    fun getWebResourceResponseFromAssets(context: Context, path: String): WebResourceResponse? {
        return try {
            val inputStream = context.assets.open(path)
            val mimeType = URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
            WebResourceResponse(mimeType, "UTF-8", inputStream)
        } catch (e: IOException) {
            null
        }
    }

    fun isLocalNetworkUrl(urlString: String): Boolean {
        val uri = try { urlString.toUri() } catch (_: Exception) { null } ?: return false
        val host = uri.host ?: return false

        return host.endsWith(".local", ignoreCase = true) || host.equals("localhost", ignoreCase = true) || try {
            val address = InetAddress.getByName(host)
            address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress
        } catch (_: Exception) {
            false
        }
    }
}
