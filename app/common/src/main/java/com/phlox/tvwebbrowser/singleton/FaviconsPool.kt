package com.phlox.tvwebbrowser.singleton

import com.phlox.tvwebbrowser.net.AppProxy
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.utils.FaviconExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

object FaviconsPool {
    const val FAVICONS_DIR = "favicons"
    const val FAVICON_PREFERRED_SIDE_SIZE = 120
    private val TAG: String = FaviconsPool::class.java.simpleName

    val faviconExtractor = FaviconExtractor()
    var databaseDelegate: DatabaseDelegate = object : DatabaseDelegate {}

    interface DatabaseDelegate {
        /** The favicon file of the host, null if it has none */
        suspend fun findFavicon(host: String): String? = null
        suspend fun setFavicon(host: String, fileName: String) {}
    }

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    suspend fun get(urlOrHost: String): Bitmap? {
        Log.d(TAG, "get: $urlOrHost")
        if (!urlOrHost.startsWith("http://", true) && !urlOrHost.startsWith("https://", true)) {
            //host passed?
            if (urlOrHost.contains("://")) {
                //not http or https
                return null
            }
            //try https first
            val httpsResult = get("https://$urlOrHost")
            if (httpsResult != null) {
                return httpsResult
            }
            return get("http://$urlOrHost")
        }
        try {
            val urlObj = URL(urlOrHost)
            val host = urlObj.host
            if (host != null) {
                val hostBitmap = cache.get(host)
                if (hostBitmap != null) {
                    return hostBitmap
                }
                val faviconFileName = databaseDelegate.findFavicon(host)
                if (faviconFileName != null) {
                    Log.d(TAG, "get: favicon found in db for $host")
                    val bitmap = withContext(Dispatchers.IO) {
                        val favIconsDir =
                            File(favIconsDir())
                        if (!favIconsDir.exists() && !favIconsDir.mkdir()) return@withContext null
                        val faviconFile = File(favIconsDir, faviconFileName)
                        if (faviconFile.exists()) {
                            BitmapFactory.decodeFile(faviconFile.absolutePath)
                        } else {
                            null
                        }
                    }
                    if (bitmap != null) {
                        Log.d(TAG, "get: favicon loaded from file for $host")
                        cache.put(host, bitmap)
                        return bitmap
                    }
                }
                Log.d(TAG, "get: no saved favicon for $host")

                val favicons = try {
                    withContext(Dispatchers.IO) { faviconExtractor.extractFavIconsFromURL(urlObj) }
                } catch (e: Exception) {
                    e.printStackTrace()
                    ArrayList()
                }
                Log.d(TAG, "get: favicons found: ${favicons.size}")
                while (favicons.isNotEmpty()) {
                    val icon = chooseNearestSizeIcon(favicons, FAVICON_PREFERRED_SIDE_SIZE, FAVICON_PREFERRED_SIDE_SIZE)!!
                    val bitmap = try {
                        downloadIcon(icon)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                    if (bitmap != null) {
                        Log.d(TAG, "get: favicon downloaded for $host")
                        cache.put(host, bitmap)
                        saveFavicon(host, bitmap)
                        return bitmap
                    } else {
                        Log.d(TAG, "get: favicon download failed for ${icon.src}")
                    }
                    favicons.remove(icon)
                }
                //the web engine might have reported an icon meanwhile
                return cache.get(host)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Remembers the icon a web engine reported for a page, unless an icon of its host is already known:
     * icons found in the page HTML are usually bigger than the ones engines report.
     * @param persist false to keep it only in memory (incognito mode)
     */
    suspend fun putIfAbsent(url: String, icon: Bitmap, persist: Boolean) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        val host = try { URL(url).host } catch (e: Exception) { null }
        if (host.isNullOrEmpty() || cache.get(host) != null) return
        val knownFavicon = databaseDelegate.findFavicon(host)
        if (knownFavicon != null && withContext(Dispatchers.IO) { File(favIconsDir(), knownFavicon).exists() }) return
        //get() might have found an icon in the HTML meanwhile
        if (cache.get(host) != null) return
        Log.d(TAG, "putIfAbsent: favicon received from the web engine for $host")
        cache.put(host, icon)
        if (persist) {
            try {
                saveFavicon(host, icon)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clear() {
        cache.evictAll()
    }

    fun favIconsDir(): String {
        return AppContext.get().cacheDir.absolutePath + File.separator + FAVICONS_DIR
    }

    private suspend fun saveFavicon(host: String, bitmap: Bitmap) {
        val faviconFileName = writeFavicon(host, bitmap) ?: return
        databaseDelegate.setFavicon(host, faviconFileName)
    }

    private suspend fun writeFavicon(host: String, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val favIconsDir = File(favIconsDir())
        if (!favIconsDir.exists() && !favIconsDir.mkdir()) return@withContext null
        val faviconFileName = host.hashCode().toString() + ".png"
        val faviconFile = File(favIconsDir, faviconFileName)
        if (faviconFile.exists()) {
            faviconFile.delete()
        }
        faviconFile.createNewFile()
        faviconFile.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        faviconFileName
    }

    private suspend fun downloadIcon(iconInfo: FaviconExtractor.IconInfo): Bitmap? = withContext(Dispatchers.IO) {
        val url = URL(iconInfo.src)
        val connection = AppProxy.open(url)
        connection.connect()
        val input = connection.getInputStream()
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeStream(input, null, options)
        input.close()
        val width = options.outWidth
        val height = options.outHeight
        val scale = Math.max(width / 512, height / 512)
        options.inJustDecodeBounds = false
        options.inSampleSize = scale
        val input2 = AppProxy.open(url).getInputStream()
        val bitmap = BitmapFactory.decodeStream(input2, null, options)
        input2.close()
        return@withContext bitmap
    }

    private fun chooseNearestSizeIcon(icons: List<FaviconExtractor.IconInfo>, w: Int, h: Int): FaviconExtractor.IconInfo? {
        var nearestIcon: FaviconExtractor.IconInfo? = null
        var nearestDiff = Int.MAX_VALUE
        for (icon in icons) {
            val diff = Math.abs(icon.width - w) + Math.abs(icon.height - h)
            if (diff < nearestDiff) {
                nearestDiff = diff
                nearestIcon = icon
            }
        }
        return nearestIcon
    }
}