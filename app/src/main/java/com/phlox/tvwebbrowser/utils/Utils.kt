package com.phlox.tvwebbrowser.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Parcel
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream


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

    fun isInstalledByGooglePlay(context: Context): Boolean {
        // A list with valid installers package name
        val validInstallers = ArrayList(Arrays.asList("com.android.vending", "com.google.android.feedback"))
        val installer = context.packageManager.getInstallerPackageName(context.packageName)
        return installer != null && validInstallers.contains(installer)
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

    fun unzipFile(zipFile: File, targetDirectory: File, progress: (progress: Int, fileName: String) -> Unit) {
        val totalLen = zipFile.length()
        var alreadyUncompressed: Long = 0
        val zis = ZipInputStream(BufferedInputStream(FileInputStream(zipFile)))
        try {
            var ze: ZipEntry?
            var count: Int
            val buffer = ByteArray(1024)
            while (true) {
                ze = zis.nextEntry
                if (ze == null) break
                val file = File(targetDirectory, ze.name)
                val dir = if (ze.isDirectory) file else file.parentFile
                if (!dir.isDirectory && !dir.mkdirs()) throw FileSystemException(dir, null, "Failed to ensure directory: " + dir.absolutePath)
                if (ze.isDirectory) continue
                var readen = 0
                FileOutputStream(file).use {
                    while (zis.read(buffer).also { count = it } != -1) {
                        it.write(buffer, 0, count)
                        if (ze.size > 0) {
                            readen += count
                            val entryPercents = readen * ze.compressedSize * 100 / (ze.size * totalLen)
                            val percent = (alreadyUncompressed * 100 / totalLen + entryPercents).toInt()
                            progress(percent, ze.name)
                        }
                    }
                }

                alreadyUncompressed += ze.compressedSize
                val percent = (alreadyUncompressed * 100 / totalLen).toInt()
                progress(percent, ze.name)

                // if time should be restored as well
                val time: Long = ze.time
                if (time > 0) file.setLastModified(time)
            }
        } finally {
            zis.close()
        }
    }

}
