package com.phlox.tvwebbrowser.browser.adblock

import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.adblock.MatchResult
import com.phlox.tvwebbrowser.adblock.ResourceType
import com.phlox.tvwebbrowser.adblock.psl.PublicSuffixList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AdBlockServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A server of lists: the content of each url, or a failure */
    class FakeFetcher : ListFetcher {
        val lists = HashMap<String, String>()
        val failing = HashSet<String>()
        val fetched = ArrayList<String>()

        override fun fetch(url: String, etag: String?, lastModified: String?, destination: File): ListFetcher.Result {
            fetched += url
            if (url in failing) throw IOException("offline")
            val content = lists[url] ?: throw IOException("404")
            val tag = content.hashCode().toString()
            if (etag == tag) return ListFetcher.Result.NotModified
            destination.writeText(content)
            return ListFetcher.Result.Downloaded(tag, null)
        }
    }

    private val easyList = FilterListCatalog.find(FilterListCatalog.EASYLIST)!!.url
    private val easyPrivacy = FilterListCatalog.find(FilterListCatalog.EASYPRIVACY)!!.url
    private val fetcher = FakeFetcher()
    private var time = 1_000_000_000_000L
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private val psl by lazy {
        PublicSuffixList.load(RuntimeEnvironment.getApplication().assets.open("adblock/psl.txt").use { it.readBytes() })
    }

    @Before
    fun setUp() {
        dir = File(temp.root, "adblock")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fetcher.lists[easyList] = "[Adblock Plus 2.0]\n! Title: EasyList\n! Expires: 4 days\n||ads.example.net^\n##.ad\n"
        fetcher.lists[easyPrivacy] = "! Title: EasyPrivacy\n||tracker.example.org^\$third-party\n"
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun service(language: String = "en") = AdBlockService(dir, scope, AppContext.provideConfig(), { psl }, fetcher,
        { time }, language)

    private fun AdBlockService.blocks(url: String, page: String = "https://example.com/"): Boolean {
        val e = engine ?: return false
        return e.match(url, url.substringAfter("://").substringBefore('/'), page,
            page.substringAfter("://").substringBefore('/'), ResourceType.SCRIPT) == MatchResult.BLOCK
    }

    private fun started(language: String = "en") = service(language).also { runBlocking { it.initialize() } }

    @Test
    fun firstStartDownloadsTheDefaultListsAndBuildsTheEngine() = runBlocking {
        val s = started()
        assertNull(s.engine)
        assertTrue(s.update(force = false))
        assertEquals(setOf(easyList, easyPrivacy), fetcher.fetched.toSet())
        assertTrue(s.blocks("https://ads.example.net/a.js"))
        assertTrue(s.blocks("https://tracker.example.org/t.js"))
        assertTrue(File(dir, AdBlockService.ENGINE_FILE).exists())
        val status = s.status.value
        assertEquals(time, status.lastUpdate)
        assertEquals(2, status.filters)
        assertEquals(1, status.lists.first { it.id == FilterListCatalog.EASYLIST }.filters)
        assertFalse(status.updating)
    }

    @Test
    fun regionalListOfTheLanguageIsOnByDefault() = runBlocking {
        val s = started(language = "ru")
        assertTrue(s.status.value.lists.first { it.id == "ruadlist" }.enabled)
        assertFalse(started(language = "en").status.value.lists.first { it.id == "ruadlist" }.enabled)
    }

    @Test
    fun aFailedUpdateKeepsTheListItsDateAndTheEngine() = runBlocking {
        val s = started()
        s.update(force = false)
        val engine = s.engine
        val updated = s.status.value.lastUpdate
        time += 5 * 24 * 3600_000L
        fetcher.failing += easyList
        assertFalse(s.update(force = false))
        assertSame(engine, s.engine)
        assertTrue(s.blocks("https://ads.example.net/a.js"))
        val list = s.status.value.lists.first { it.id == FilterListCatalog.EASYLIST }
        assertEquals(updated, list.lastUpdate)
        assertTrue(list.failed)
    }

    @Test
    fun aFailedDownloadIsTriedAgainLater() = runBlocking {
        fetcher.failing += easyList
        val s = started()
        s.update(force = false)
        fetcher.fetched.clear()
        s.update(force = false)
        assertFalse(easyList in fetcher.fetched)
        time += 3600_000L
        fetcher.failing.clear()
        assertTrue(s.update(force = false))
        assertTrue(easyList in fetcher.fetched)
        assertTrue(s.blocks("https://ads.example.net/a.js"))
    }

    @Test
    fun listsAreNotDownloadedBeforeTheyExpire() = runBlocking {
        val s = started()
        s.update(force = false)
        fetcher.fetched.clear()
        time += 24 * 3600_000L
        s.update(force = false)
        assertTrue(fetcher.fetched.isEmpty())
        time += 3 * 24 * 3600_000L
        s.update(force = false)
        assertTrue(easyList in fetcher.fetched)
    }

    @Test
    fun anUnchangedListKeepsTheEngine() = runBlocking {
        val s = started()
        s.update(force = false)
        val engine = s.engine
        time += 1000
        assertTrue(s.update(force = true))
        assertSame(engine, s.engine)
        assertEquals(time, s.status.value.lastUpdate)
    }

    @Test
    fun aRestartLoadsTheEngineFileWithoutTheNetwork() = runBlocking {
        val first = started()
        first.update(force = false)
        val created = first.engine!!.createdAt
        fetcher.fetched.clear()
        val second = started()
        assertEquals(created, second.engine!!.createdAt)
        assertTrue(second.blocks("https://ads.example.net/a.js"))
        assertTrue(fetcher.fetched.isEmpty())
    }

    @Test
    fun aDamagedEngineFileIsBuiltAgainFromTheLists() = runBlocking {
        started().update(force = false)
        val file = File(dir, AdBlockService.ENGINE_FILE)
        file.writeBytes(file.readBytes().copyOf(100))
        fetcher.fetched.clear()
        val s = started()
        assertNotNull(s.engine)
        assertTrue(s.blocks("https://ads.example.net/a.js"))
        assertTrue(fetcher.fetched.isEmpty())
    }

    @Test
    fun turningAListOffBuildsTheEngineWithoutIt() = runBlocking {
        val s = started()
        s.update(force = false)
        s.setListEnabled(FilterListCatalog.EASYPRIVACY, false).join()
        assertFalse(s.blocks("https://tracker.example.org/t.js"))
        assertTrue(s.blocks("https://ads.example.net/a.js"))
        s.setListEnabled(FilterListCatalog.EASYPRIVACY, true).join()
        assertTrue(s.blocks("https://tracker.example.org/t.js"))
    }

    @Test
    fun theUsersOwnList() = runBlocking {
        val s = started()
        s.update(force = false)
        fetcher.lists["https://my.example/list.txt"] = "||mine.example.com^\n"
        s.setCustomUrl(" https://my.example/list.txt ").join()
        assertTrue(s.blocks("https://mine.example.com/a.js"))
        assertEquals("https://my.example/list.txt", s.status.value.customUrl)
        s.setCustomUrl("").join()
        assertFalse(s.blocks("https://mine.example.com/a.js"))
    }

    @Test
    fun anHtmlPageIsNotAList() = runBlocking {
        fetcher.lists[easyList] = "<!DOCTYPE html><html>Sign in to the Wi-Fi</html>"
        val s = started()
        assertFalse(s.update(force = false))
        assertFalse(s.blocks("https://ads.example.net/a.js"))
        assertTrue(s.blocks("https://tracker.example.org/t.js"))
    }

    @Test
    fun theFilesAndSettingsOfTheNativeLibraryAreRemoved() = runBlocking {
        val old = File(temp.root, AdBlockService.OLD_ENGINE_FILE).apply { writeText("x") }
        val prefs = AppContext.provideConfig().prefs
        prefs.edit().putString(AdBlockService.OLD_LIST_URL_KEY, "https://x/list.txt")
            .putLong(AdBlockService.OLD_LAST_UPDATE_KEY, 1).commit()
        started()
        assertFalse(old.exists())
        assertFalse(prefs.contains(AdBlockService.OLD_LIST_URL_KEY))
        assertFalse(prefs.contains(AdBlockService.OLD_LAST_UPDATE_KEY))
    }
}
