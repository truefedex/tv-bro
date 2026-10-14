package com.phlox.tvwebbrowser.singleton

import android.graphics.Bitmap
import com.phlox.tvwebbrowser.model.HostConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Icons reported by web engines.
 */
@RunWith(RobolectricTestRunner::class)
class FaviconsPoolTest {
    private val hosts = HashMap<String, HostConfig>()
    private val writes = ArrayList<String>()
    private lateinit var originalDelegate: FaviconsPool.DatabaseDelegate

    @Before
    fun setUp() {
        FaviconsPool.clear()
        originalDelegate = FaviconsPool.databaseDelegate
        FaviconsPool.databaseDelegate = object : FaviconsPool.DatabaseDelegate {
            override fun findByHostName(host: String) = hosts[host]
            override suspend fun update(hostConfig: HostConfig) {
                writes.add("update ${hostConfig.hostName}")
            }
            override suspend fun insert(newHostConfig: HostConfig) {
                writes.add("insert ${newHostConfig.hostName}")
                hosts[newHostConfig.hostName] = newHostConfig
            }
        }
    }

    @After
    fun tearDown() {
        FaviconsPool.databaseDelegate = originalDelegate
        FaviconsPool.clear()
    }

    private fun icon() = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

    @Test
    fun iconOfUnknownHostIsStored() = runTest {
        val icon = icon()
        FaviconsPool.putIfAbsent("https://a.com/page", icon, persist = true)
        assertEquals(listOf("insert a.com"), writes)
        val fileName = hosts["a.com"]!!.favicon!!
        assertTrue(File(FaviconsPool.favIconsDir(), fileName).exists())
        assertSame(icon, FaviconsPool.get("https://a.com/other"))
    }

    @Test
    fun knownIconIsNotReplaced() = runTest {
        val dir = File(FaviconsPool.favIconsDir()).apply { mkdirs() }
        File(dir, "big.png").writeBytes(ByteArray(1))
        hosts["a.com"] = HostConfig("a.com").apply { favicon = "big.png" }

        FaviconsPool.putIfAbsent("https://a.com/page", icon(), persist = true)
        assertEquals(emptyList<String>(), writes)
        assertEquals("big.png", hosts["a.com"]!!.favicon)
    }

    @Test
    fun cachedIconIsNotReplaced() = runTest {
        val first = icon()
        FaviconsPool.putIfAbsent("https://a.com/", first, persist = true)
        FaviconsPool.putIfAbsent("https://a.com/", icon(), persist = true)
        assertEquals(listOf("insert a.com"), writes)
        assertSame(first, FaviconsPool.get("https://a.com/"))
    }

    @Test
    fun iconIsKeptOnlyInMemoryWithoutPersist() = runTest {
        val icon = icon()
        FaviconsPool.putIfAbsent("https://a.com/", icon, persist = false)
        assertEquals(emptyList<String>(), writes)
        assertNull(hosts["a.com"])
        assertSame(icon, FaviconsPool.get("https://a.com/"))
    }

    @Test
    fun iconOfNotHttpPageIsIgnored() = runTest {
        FaviconsPool.putIfAbsent("about:blank", icon(), persist = true)
        FaviconsPool.putIfAbsent("file:///sdcard/a.html", icon(), persist = true)
        assertEquals(emptyList<String>(), writes)
    }
}
