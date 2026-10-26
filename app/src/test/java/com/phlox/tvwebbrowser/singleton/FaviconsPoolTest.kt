package com.phlox.tvwebbrowser.singleton

import android.graphics.Bitmap
import com.phlox.tvwebbrowser.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.awt.image.BufferedImage
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Icons of the pages (a fake fetcher), icons reported by web engines and saved icons.
 */
@RunWith(RobolectricTestRunner::class)
class FaviconsPoolTest {
    /** host to favicon file */
    private val hosts = ConcurrentHashMap<String, String>()
    private val writes = Collections.synchronizedList(ArrayList<String>())
    private val fetches = Collections.synchronizedList(ArrayList<URL>())
    /** what the fake network finds on a page */
    @Volatile
    private var pageIcon: Bitmap? = null
    @Volatile
    private var fetchDelayMs = 0L
    private lateinit var originalDelegate: FaviconsPool.DatabaseDelegate
    private lateinit var originalFetcher: FaviconsPool.Fetcher
    private val config get() = AppContext.provideConfig()

    @Before
    fun setUp() {
        FaviconsPool.clear()
        File(FaviconsPool.favIconsDir()).deleteRecursively()
        originalDelegate = FaviconsPool.databaseDelegate
        originalFetcher = FaviconsPool.fetcher
        FaviconsPool.databaseDelegate = object : FaviconsPool.DatabaseDelegate {
            override suspend fun findFavicon(host: String): String? = hosts[host]
            override suspend fun setFavicon(host: String, fileName: String) {
                writes.add("set $host")
                hosts[host] = fileName
            }
        }
        FaviconsPool.fetcher = FaviconsPool.Fetcher { url ->
            fetches.add(url)
            if (fetchDelayMs > 0) Thread.sleep(fetchDelayMs)
            pageIcon
        }
    }

    @After
    fun tearDown() {
        FaviconsPool.databaseDelegate = originalDelegate
        FaviconsPool.fetcher = originalFetcher
        config.incognitoMode = false
        FaviconsPool.clear()
    }

    private fun icon(side: Int) = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition()) {
            assertTrue("timed out", System.currentTimeMillis() < end)
            Thread.sleep(20)
        }
    }

    /** A real PNG file of [side] pixels, saved as the icon of [host] */
    private fun storedIcon(host: String, side: Int, ageMs: Long): File {
        val dir = File(FaviconsPool.favIconsDir()).apply { mkdirs() }
        val file = File(dir, "old.png")
        ImageIO.write(BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB), "png", file)
        file.setLastModified(System.currentTimeMillis() - ageMs)
        hosts[host] = file.name
        return file
    }

    @Test
    fun pageIconIsFoundAndStored() = runTest {
        val page = icon(128)
        pageIcon = page
        assertSame(page, FaviconsPool.get("https://a.com/page"))
        assertEquals(listOf("set a.com"), writes)
        assertTrue(File(FaviconsPool.favIconsDir(), hosts["a.com"]!!).exists())
        //the host is known now
        assertSame(page, FaviconsPool.get("https://a.com/other"))
        assertEquals(1, fetches.size)
    }

    @Test
    fun bigIconIsScaledDown() = runTest {
        pageIcon = icon(512)
        val icon = FaviconsPool.get("https://a.com/")!!
        assertEquals(FaviconsPool.MAX_STORED_SIZE, icon.width)
        assertEquals(FaviconsPool.MAX_STORED_SIZE, icon.height)
    }

    @Test
    fun smallEngineIconGivesWayToBiggerPageIcon() = runTest {
        val page = icon(128)
        pageIcon = page
        FaviconsPool.onEngineIcon("https://a.com/page", icon(16))
        assertEquals(1, fetches.size)
        assertEquals(listOf("set a.com"), writes)
        assertSame(page, FaviconsPool.get("https://a.com/"))
    }

    @Test
    fun biggerEngineIconIsKept() = runTest {
        pageIcon = icon(16)
        val engine = icon(64)
        FaviconsPool.onEngineIcon("https://a.com/page", engine)
        assertSame(engine, FaviconsPool.get("https://a.com/"))
        assertEquals("a.com", hosts.keys.single())
    }

    @Test
    fun engineIconIsKeptWhenThePageHasNone() = runTest {
        val engine = icon(16)
        FaviconsPool.onEngineIcon("https://a.com/page", engine)
        assertEquals(listOf("set a.com"), writes)
        assertSame(engine, FaviconsPool.get("https://a.com/"))
    }

    @Test
    fun knownBiggerIconIsNotReplaced() = runTest {
        val page = icon(128)
        pageIcon = page
        FaviconsPool.get("https://a.com/")
        FaviconsPool.onEngineIcon("https://a.com/page", icon(16))
        assertEquals(1, fetches.size)
        assertEquals(listOf("set a.com"), writes)
        assertSame(page, FaviconsPool.get("https://a.com/"))
    }

    @Test
    fun iconOfNotHttpPageIsIgnored() = runTest {
        FaviconsPool.onEngineIcon("about:blank", icon(16))
        FaviconsPool.onEngineIcon("file:///sdcard/a.html", icon(16))
        assertNull(FaviconsPool.get("file:///sdcard/a.html"))
        assertEquals(emptyList<String>(), writes)
        assertEquals(emptyList<URL>(), fetches)
    }

    @Test
    fun incognitoIconsAreKeptOnlyInMemory() = runTest {
        config.incognitoMode = true
        val page = icon(128)
        pageIcon = page
        assertSame(page, FaviconsPool.get("https://a.com/"))
        FaviconsPool.onEngineIcon("https://b.com/", icon(16))
        assertEquals(emptyList<String>(), writes)
        assertSame(page, FaviconsPool.get("https://a.com/"))
    }

    @Test
    fun parallelRequestsOfOneHostShareOneFetch() = runTest {
        val page = icon(128)
        pageIcon = page
        fetchDelayMs = 200
        val icons = (1..5).map { async { FaviconsPool.get("https://a.com/page$it") } }.awaitAll()
        icons.forEach { assertSame(page, it) }
        assertEquals(1, fetches.size)
    }

    @Test
    fun hostWithoutIconIsNotAskedAgainSoon() = runTest {
        assertNull(FaviconsPool.get("a.com"))
        //https, then http
        assertEquals(listOf(URL("https://a.com/"), URL("http://a.com/")), fetches)
        assertNull(FaviconsPool.get("a.com"))
        assertNull(FaviconsPool.get("https://a.com/page"))
        assertEquals(2, fetches.size)
    }

    @Test
    fun oldSmallStoredIconIsLookedUpAgain() = runTest {
        val old = storedIcon("a.com", 16, ageMs = 30L * 24 * 60 * 60 * 1000)
        pageIcon = icon(128)
        //the saved icon is given at once, the bigger one is found in the background
        assertEquals(16, FaviconsPool.get("https://a.com/")!!.width)
        waitUntil { hosts["a.com"] != "old.png" }
        assertEquals(128, FaviconsPool.get("https://a.com/")!!.width)
        assertTrue(!old.exists())
    }

    @Test
    fun failedLookUpOfSmallIconIsNotRepeatedForAWhile() = runTest {
        val old = storedIcon("a.com", 16, ageMs = 30L * 24 * 60 * 60 * 1000)
        val modified = old.lastModified()
        FaviconsPool.get("https://a.com/")
        waitUntil { old.lastModified() != modified }
        assertEquals(1, fetches.size)
        assertEquals("old.png", hosts["a.com"])
        //a new process (empty memory) does not look again
        FaviconsPool.clear()
        FaviconsPool.get("https://a.com/")
        Thread.sleep(200)
        assertEquals(1, fetches.size)
    }

    @Test
    fun bigStoredIconIsScaledDown() = runTest {
        storedIcon("a.com", 512, ageMs = 0)
        val icon = FaviconsPool.get("https://a.com/")!!
        assertEquals(FaviconsPool.MAX_STORED_SIZE, icon.width)
        assertEquals(emptyList<URL>(), fetches)
    }

    @Test
    fun engineIconIsShownWhileThePageIsLookedAt() = runTest {
        val page = icon(128)
        pageIcon = page
        fetchDelayMs = 300
        val engine = icon(16)
        val shown = AtomicInteger()
        //on another thread: waitUntil blocks this one
        val done = async(Dispatchers.Default) { FaviconsPool.onEngineIcon("https://a.com/page", engine) { shown.incrementAndGet() } }
        waitUntil { shown.get() == 1 }
        assertSame(engine, FaviconsPool.get("https://a.com/"))
        done.await()
        assertSame(page, FaviconsPool.get("https://a.com/"))
        assertEquals(1, fetches.size)
    }

    @Test
    fun recentSmallStoredIconIsNotLookedUp() = runTest {
        storedIcon("a.com", 16, ageMs = 0)
        pageIcon = icon(128)
        assertEquals(16, FaviconsPool.get("https://a.com/")!!.width)
        Thread.sleep(200)
        assertEquals(emptyList<URL>(), fetches)
        assertNotEquals(null, hosts["a.com"])
    }
}
