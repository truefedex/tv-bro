package com.phlox.tvwebbrowser.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*


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

    fun convertJsonToBundle(json: JSONObject): Bundle? {
        val bundle = Bundle()
        try {
            val iterator = json.keys()
            loop@ while (iterator.hasNext()) {
                val key = iterator.next() as String
                val value = json[key]
                when (value.javaClass.simpleName) {
                    "String" -> bundle.putString(key, value as String)
                    "Integer" -> bundle.putInt(key, (value as Int))
                    "Long" -> bundle.putLong(key, (value as Long))
                    "Boolean" -> bundle.putBoolean(key, (value as Boolean))
                    "JSONObject" -> bundle.putBundle(key, convertJsonToBundle(value as JSONObject))
                    "Float" -> bundle.putFloat(key, (value as Float))
                    "Double" -> bundle.putDouble(key, (value as Double))
                    "JSONArray" -> {
                        val jsArr = value as JSONArray
                        if (jsArr.length() == 0) continue@loop
                        val first = jsArr.get(0)
                        when (first.javaClass.simpleName) {
                            "String" -> bundle.putStringArray(key, Array<String>(jsArr.length()) {
                                            jsArr.getString(it)
                                        })
                            //IntArray is more suitable but in our case (storing webview state) we need bytes
                            "Integer" -> bundle.putByteArray(key, ByteArray(jsArr.length()) {
                                jsArr.getInt(it).toByte()
                            })
                            "Long" -> bundle.putLongArray(key, LongArray(jsArr.length()) {
                                jsArr.getLong(it)
                            })
                            "Boolean" -> bundle.putBooleanArray(key, BooleanArray(jsArr.length()) {
                                jsArr.getBoolean(it)
                            })
                            "Float" -> bundle.putFloatArray(key, FloatArray(jsArr.length()) {
                                jsArr.getDouble(it).toFloat()
                            })
                            "Double" -> bundle.putDoubleArray(key, DoubleArray(jsArr.length()) {
                                jsArr.getDouble(it)
                            })
                        }
                    }
                    else -> bundle.putString(key, value.javaClass.simpleName)
                }
            }
            return bundle
        } catch (e: JSONException) {
            e.printStackTrace()
            return null
        }
    }
}
