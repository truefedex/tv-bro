package com.phlox.tvwebbrowser.utils

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebResourceResponse
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.URLConnection
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

    fun jsonToMap(json: String): Map<String, Any?> {
        val map: MutableMap<String, Any?> = HashMap()
        try {
            val jsonObject = JSONObject(json)
            val keysItr = jsonObject.keys()
            while (keysItr.hasNext()) {
                val key = keysItr.next()
                var value = jsonObject[key]
                if (value is JSONArray) {
                    value = jsonToList(value.toString())
                } else if (value is JSONObject) {
                    value = jsonToMap(value.toString())
                }
                map[key] = value
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return map
    }

    private fun jsonToList(string: String): List<Any?> {
        val list: MutableList<Any?> = ArrayList()
        try {
            val jsonArray = JSONArray(string)
            for (i in 0 until jsonArray.length()) {
                var value = jsonArray[i]
                if (value is JSONArray) {
                    value = jsonToList(value.toString())
                } else if (value is JSONObject) {
                    value = jsonToMap(value.toString())
                }
                list.add(value)
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return list
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
