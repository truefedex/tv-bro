package com.phlox.tvwebbrowser.singleton

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.utils.FaviconExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

object FaviconsPool {
    const val FAVICONS_DIR = "favicons"
    const val FAVICON_PREFERRED_SIDE_SIZE = 120
    private val TAG: String = FaviconsPool::class.java.simpleName

    val faviconExtractor = FaviconExtractor()

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
                val hostConfig = AppDatabase.db.hostsDao().findByHostName(host)
                if (hostConfig != null) {
                    val faviconFileName = hostConfig.favicon
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
                } else {
                    Log.d(TAG, "get: favicon not found in db for $host")
                }

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
                        saveFavicon(host, bitmap, hostConfig)
                        return bitmap
                    } else {
                        Log.d(TAG, "get: favicon download failed for ${icon.src}")
                    }
                    favicons.remove(icon)
                }
                //try to get favicon from webview
                withContext(Dispatchers.Main) {
                    WebView(TVBro.instance).apply {
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                                super.onReceivedIcon(view, icon)
                                if (icon != null) {
                                    Log.d(TAG, "get: favicon received from webview for $host")
                                    cache.put(host, icon)
                                    runBlocking {
                                        saveFavicon(host, icon, hostConfig)
                                    }
                                }
                            }
                        }
                        loadUrl(urlOrHost)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun clear() {
        cache.evictAll()
    }

    fun favIconsDir(): String {
        return TVBro.instance.cacheDir.absolutePath + File.separator + FAVICONS_DIR
    }

    private suspend fun saveFavicon(host: String, bitmap: Bitmap, hostConfig: HostConfig?) = withContext(Dispatchers.IO) {
        val favIconsDir = File(favIconsDir())
        if (!favIconsDir.exists() && !favIconsDir.mkdir()) return@withContext
        val faviconFileName = host.hashCode().toString() + ".png"
        val faviconFile = File(favIconsDir, faviconFileName)
        if (faviconFile.exists()) {
            faviconFile.delete()
        }
        faviconFile.createNewFile()
        faviconFile.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        if (hostConfig != null) {
            hostConfig.favicon = faviconFileName
            AppDatabase.db.hostsDao().update(hostConfig)
        } else {
            val newHostConfig = HostConfig(host)
            newHostConfig.favicon = faviconFileName
            AppDatabase.db.hostsDao().insert(newHostConfig)
        }
    }

    private suspend fun downloadIcon(iconInfo: FaviconExtractor.IconInfo): Bitmap? = withContext(Dispatchers.IO) {
        val url = URL(iconInfo.src)
        val connection = url.openConnection()
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
        val input2 = url.openConnection().getInputStream()
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