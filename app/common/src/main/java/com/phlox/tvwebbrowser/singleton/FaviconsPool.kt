package com.phlox.tvwebbrowser.singleton

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.util.LruCache
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.utils.FaviconExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The favicons of hosts: in memory, in files of [favIconsDir] (named in the host configs) and found on the
 * network in the HTML of a page (its `<link>`s and web manifest). Loads of one host are shared by all callers.
 */
object FaviconsPool {
    const val FAVICONS_DIR = "favicons"
    /**
     * The side icons are chosen for: the tiles of the home page (about 150 px on a 4K TV) and of the
     * home screen channels (about 134 px)
     */
    const val TARGET_SIZE = 128
    /** Bigger icons are scaled down to it before they are kept */
    const val MAX_STORED_SIZE = 192
    /** Smaller icons look blurry: more candidates are tried, and saved ones are looked up again later */
    const val MIN_SHARP_SIZE = 64
    private const val MAX_DOWNLOAD_ATTEMPTS = 4
    private const val MAX_ICON_BYTES = 1024 * 1024
    private const val MAX_PARALLEL_FETCHES = 4
    /** A host whose page gave no icon is not asked again for this long (lists bind its rows often) */
    private const val FAILURE_MEMORY_MS = 10 * 60 * 1000L
    /** A saved icon smaller than [MIN_SHARP_SIZE] is looked up again when its file is older */
    private const val UPGRADE_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L
    private val TAG: String = FaviconsPool::class.java.simpleName

    val faviconExtractor = FaviconExtractor()
    var databaseDelegate: DatabaseDelegate = object : DatabaseDelegate {}

    interface DatabaseDelegate {
        /** The favicon file of the host, null if it has none */
        suspend fun findFavicon(host: String): String? = null
        suspend fun setFavicon(host: String, fileName: String) {}
    }

    /** Finds the best icon of a page on the network (blocking); tests replace it */
    fun interface Fetcher {
        fun fetch(pageUrl: URL): Bitmap?
    }

    var fetcher: Fetcher = Fetcher { fetchFromNetwork(it) }

    /** [provisional]: reported by a web engine, the page was not looked at yet */
    private class Entry(val bitmap: Bitmap, val provisional: Boolean)

    private val cache: LruCache<String, Entry> = object : LruCache<String, Entry>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Entry): Int {
            return value.bitmap.byteCount
        }
    }

    /** Loads outlive their callers: a row that is recycled must not cancel the load of the other ones */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loads = HashMap<String, Deferred<Bitmap?>>()
    private val failures = ConcurrentHashMap<String, Long>()
    private val fetches = Semaphore(MAX_PARALLEL_FETCHES)

    private val Bitmap.side: Int get() = max(width, height)

    /** The icon of a host ("example.com") or of a page (an http or https url), null if none is found */
    suspend fun get(urlOrHost: String): Bitmap? {
        val host: String
        val pageUrls: List<URL>
        try {
            if (isHttp(urlOrHost)) {
                val url = URL(urlOrHost)
                host = url.host
                pageUrls = listOf(url)
            } else {
                //not http or https
                if (urlOrHost.contains("://")) return null
                host = urlOrHost
                pageUrls = listOf(URL("https://$host/"), URL("http://$host/"))
            }
        } catch (e: Exception) {
            return null
        }
        if (host.isEmpty()) return null
        //a provisional icon is shown while onEngineIcon looks at the page
        cache.get(host)?.let { return it.bitmap }
        return load(host, pageUrls)
    }

    /**
     * Takes the icon a web engine reported for a page. It is shown at once ([onShown] is called when [get]
     * gives it), but the page's own icons are looked up too (engines report small ones): the bigger one is kept.
     */
    suspend fun onEngineIcon(url: String, icon: Bitmap, onShown: () -> Unit = {}) {
        if (!isHttp(url)) return
        val pageUrl = try { URL(url) } catch (e: Exception) { null } ?: return
        val host = pageUrl.host
        if (host.isNullOrEmpty()) return
        val engineIcon = scaleDown(icon)
        val known = cache.get(host)
        if (known != null && known.bitmap.side >= engineIcon.side) return
        if (known == null || known.provisional) {
            cache.put(host, Entry(engineIcon, provisional = true))
            onShown()
        }
        load(host, listOf(pageUrl))
        //still provisional when the page gave none
        val found = cache.get(host)
        if (found == null || found.provisional || found.bitmap.side < engineIcon.side) {
            Log.d(TAG, "onEngineIcon: the icon of the web engine is kept for $host")
            store(host, engineIcon)
        }
    }

    fun clear() {
        cache.evictAll()
        failures.clear()
    }

    fun favIconsDir(): String {
        return AppContext.get().cacheDir.absolutePath + File.separator + FAVICONS_DIR
    }

    private fun isHttp(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    private suspend fun load(host: String, pageUrls: List<URL>): Bitmap? {
        val deferred = synchronized(loads) {
            loads[host] ?: scope.async(start = CoroutineStart.LAZY) { doLoad(host, pageUrls) }.also { load ->
                loads[host] = load
                load.invokeOnCompletion { synchronized(loads) { if (loads[host] === load) loads.remove(host) } }
            }
        }
        deferred.start()
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "load: $host", e)
            null
        }
    }

    private suspend fun doLoad(host: String, pageUrls: List<URL>): Bitmap? {
        cache.get(host)?.takeIf { !it.provisional }?.let { return it.bitmap }
        val fileName = databaseDelegate.findFavicon(host)
        if (fileName != null) {
            val file = File(favIconsDir(), fileName)
            //older versions saved icons of up to about 1000 px
            val stored = if (file.isFile) {
                try { decodeIcon(file.readBytes()) } catch (e: Exception) { null }
            } else null
            if (stored != null) {
                cache.put(host, Entry(stored, provisional = false))
                if (stored.side < MIN_SHARP_SIZE && System.currentTimeMillis() - file.lastModified() > UPGRADE_INTERVAL_MS) {
                    scope.launch { upgrade(host, pageUrls, file, stored) }
                }
                return stored
            }
        }
        val failedAt = failures[host]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_MEMORY_MS) {
            return cache.get(host)?.bitmap
        }
        val fetched = fetch(pageUrls)
        if (fetched == null) {
            Log.d(TAG, "load: no icon found for $host")
            failures[host] = System.currentTimeMillis()
            //the web engine might have reported an icon meanwhile
            return cache.get(host)?.bitmap
        }
        failures.remove(host)
        store(host, fetched)
        return fetched
    }

    /** Looks for a bigger icon than the small [stored] one; the UI shows it when it asks for the icon again */
    private suspend fun upgrade(host: String, pageUrls: List<URL>, file: File, stored: Bitmap) {
        val fetched = fetch(pageUrls)
        val current = cache.get(host)?.bitmap ?: stored
        if (fetched != null && fetched.side > current.side) {
            Log.d(TAG, "upgrade: a bigger icon found for $host")
            store(host, fetched)
        } else {
            //not again for a while
            file.setLastModified(System.currentTimeMillis())
        }
    }

    private suspend fun fetch(pageUrls: List<URL>): Bitmap? = fetches.withPermit {
        for (pageUrl in pageUrls) {
            val bitmap = try {
                withContext(Dispatchers.IO) { fetcher.fetch(pageUrl) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "fetch: $pageUrl: $e")
                null
            }
            if (bitmap != null) return@withPermit scaleDown(bitmap)
        }
        null
    }

    private suspend fun store(host: String, bitmap: Bitmap) {
        cache.put(host, Entry(bitmap, provisional = false))
        if (AppContext.provideConfig().incognitoMode) return
        try {
            saveFavicon(host, bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "store: $host", e)
        }
    }

    private suspend fun saveFavicon(host: String, bitmap: Bitmap) {
        val previous = databaseDelegate.findFavicon(host)
        val fileName = withContext(Dispatchers.IO) { writeFavicon(host, bitmap) } ?: return
        databaseDelegate.setFavicon(host, fileName)
        if (previous != null && previous != fileName) {
            withContext(Dispatchers.IO) { File(favIconsDir(), previous).delete() }
        }
    }

    private fun writeFavicon(host: String, bitmap: Bitmap): String? {
        val favIconsDir = File(favIconsDir())
        if (!favIconsDir.exists() && !favIconsDir.mkdirs()) return null
        val faviconFileName = fileNameOf(host)
        val faviconFile = File(favIconsDir, faviconFileName)
        //readers (the channels of the home screen) never see a half written file
        val tempFile = File(favIconsDir, "$faviconFileName.tmp")
        tempFile.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        //renameTo does not replace a file on every file system
        if (!tempFile.renameTo(faviconFile) && !(faviconFile.delete() && tempFile.renameTo(faviconFile))) {
            tempFile.delete()
            return null
        }
        return faviconFileName
    }

    /** A stable name of the host's file (hashCode() of different hosts can be the same) */
    private fun fileNameOf(host: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(host.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) } + ".png"
    }

    /** Tries the icons of the page from the most fitting one and gives the biggest of those it got */
    private fun fetchFromNetwork(pageUrl: URL): Bitmap? {
        val icons = faviconExtractor.extractFavIconsFromURL(pageUrl)
        var best: Bitmap? = null
        for (icon in FaviconExtractor.rank(icons, TARGET_SIZE, MIN_SHARP_SIZE).take(MAX_DOWNLOAD_ATTEMPTS)) {
            val bitmap = try {
                downloadIcon(icon.src)
            } catch (e: Exception) {
                Log.d(TAG, "fetchFromNetwork: ${icon.src}: $e")
                null
            } ?: continue
            if (best == null || bitmap.side > best.side) best = bitmap
            if (best.side >= MIN_SHARP_SIZE) break
        }
        return best
    }

    private fun downloadIcon(src: String): Bitmap? {
        val bytes = if (src.startsWith("data:", true)) {
            val comma = src.indexOf(',')
            if (comma < 0 || !src.substring(0, comma).endsWith(";base64", true)) return null
            Base64.decode(src.substring(comma + 1), Base64.DEFAULT)
        } else {
            val connection = FaviconExtractor.open(URL(src), "image/png,image/*;q=0.9,*/*;q=0.8")
            if (connection is HttpURLConnection && connection.responseCode >= 400) {
                connection.errorStream?.close()
                return null
            }
            connection.getInputStream().use { readLimited(it) } ?: return null
        }
        return decodeIcon(bytes)
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return output.toByteArray()
            if (output.size() + count > MAX_ICON_BYTES) return null
            output.write(buffer, 0, count)
        }
    }

    /** Decodes the picture once, at the smallest power of two scale still bigger than [MAX_STORED_SIZE] */
    private fun decodeIcon(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val side = max(options.outWidth, options.outHeight)
        if (side <= 0) return null
        var sampleSize = 1
        while (side / (sampleSize * 2) >= MAX_STORED_SIZE) sampleSize *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { scaleDown(it) }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        if (bitmap.side <= MAX_STORED_SIZE) return bitmap
        val scale = MAX_STORED_SIZE.toFloat() / bitmap.side
        return Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }
}
