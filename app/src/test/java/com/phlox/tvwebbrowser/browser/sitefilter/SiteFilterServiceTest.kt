package com.phlox.tvwebbrowser.browser.sitefilter

import android.net.Uri
import androidx.room.Room
import com.phlox.tvwebbrowser.adblock.psl.PublicSuffixList
import com.phlox.tvwebbrowser.browser.adblock.AdBlockServiceTest
import com.phlox.tvwebbrowser.data.HostConfigRepository
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.webengine.SiteBlock
import com.phlox.tvwebbrowser.webengine.SiteCategory
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SiteFilterServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val fetcher = AdBlockServiceTest.FakeFetcher()
    private var time = 1_000_000_000_000L
    private var wanted = SiteFilterService.Wanted(SiteCategory.listed.toSet(), "")
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var db: AppDatabase
    private lateinit var hosts: HostConfigRepository
    private var kidsShown = true
    private val psl by lazy {
        PublicSuffixList.load(RuntimeEnvironment.getApplication().assets.open("adblock/psl.txt").use { it.readBytes() })
    }

    @Before
    fun setUp() {
        dir = File(temp.root, "sitefilter")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fetcher.lists[SiteCategory.ADULT.url!!] = "# Title: adult\nadult.example\n"
        fetcher.lists[SiteCategory.GAMBLING.url!!] = "# Title: gambling\ncasino.example\nbet.example.co.uk\n"
        fetcher.lists[SiteCategory.THREATS.url!!] = "scam.example\n"
        fetcher.lists[SiteCategory.BYPASS.url!!] = "vpn.example\n"
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        hosts = HostConfigRepository(db.hostsDao())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun service(shown: Boolean = true) = SiteFilterService(dir, scope, fetcher, { time }) { wanted }
        .also { runBlocking { it.initialize(); it.setShown(shown).join() } }

    private fun AppSiteFilter.blocks(url: String, mainFrame: Boolean = true, page: String? = null): SiteBlock? =
        check(Uri.parse(url), mainFrame, page?.let(Uri::parse))

    private fun filterOf(service: SiteFilterService) =
        AppSiteFilter({ service.filter }, { service.mask }, { hosts }, { psl }, { time }, { kidsShown })

    @Test
    fun theListsWantedAreDownloadedAndBlockTheirSitesWithSubdomains() = runBlocking {
        val service = service()
        val filter = filterOf(service)
        //nothing downloaded yet: nothing blocked, and the settings say so
        assertNull(filter.blocks("https://adult.example/"))
        assertEquals(4, service.status.value.missing)
        assertTrue(service.update(force = false))
        assertEquals(0, service.status.value.missing)
        assertEquals(5, service.status.value.domains)
        assertEquals(SiteBlock(SiteCategory.ADULT), filter.blocks("https://www.adult.example/page"))
        assertEquals(SiteBlock(SiteCategory.GAMBLING), filter.blocks("http://m.casino.example/"))
        //requests of pages too
        assertEquals(SiteBlock(SiteCategory.THREATS), filter.blocks("https://scam.example/x.js", false, "https://news.example/"))
        assertNull(filter.blocks("https://news.example/"))
        //not web addresses
        assertNull(filter.blocks("intent://adult.example/"))
    }

    @Test
    fun onlyTheKidsProfileIsFiltered() = runBlocking {
        val service = service()
        service.update(force = false)
        val filter = filterOf(service)
        kidsShown = false
        assertFalse(filter.active)
        assertNull(filter.blocks("https://adult.example/"))
    }

    @Test
    fun aCategoryTurnedOffIsNotBlockedAndItsListNotDownloaded() = runBlocking {
        wanted = SiteFilterService.Wanted(setOf(SiteCategory.ADULT), "")
        val service = service()
        service.update(force = false)
        assertEquals(setOf(SiteCategory.ADULT.url), fetcher.fetched.toSet())
        val filter = filterOf(service)
        assertNotNull(filter.blocks("https://adult.example/"))
        assertNull(filter.blocks("https://casino.example/"))
    }

    @Test
    fun theOwnListIsACategoryOfItsOwn() = runBlocking {
        val own = "https://lists.example/mine.txt"
        fetcher.lists[own] = "0.0.0.0 cartoons.example\n"
        wanted = SiteFilterService.Wanted(emptySet(), own)
        val service = service()
        service.update(force = false)
        assertEquals(SiteBlock(SiteCategory.CUSTOM), filterOf(service).blocks("https://cartoons.example/"))
        //another address: the old list goes
        val other = "https://lists.example/other.txt"
        fetcher.lists[other] = "games.example\n"
        wanted = SiteFilterService.Wanted(emptySet(), other)
        service.onWantedChanged().join()
        assertNull(filterOf(service).blocks("https://cartoons.example/"))
        assertNotNull(filterOf(service).blocks("https://games.example/"))
    }

    @Test
    fun aMissingListIsTriedAgainSoon() = runBlocking {
        fetcher.failing += SiteCategory.ADULT.url!!
        val service = service()
        assertFalse(service.update(force = false))
        assertTrue(service.status.value.failed)
        fetcher.failing.clear()
        fetcher.fetched.clear()
        //too soon
        time += 60_000
        service.update(force = false)
        assertFalse(SiteCategory.ADULT.url in fetcher.fetched)
        time += 5 * 60_000
        assertTrue(service.update(force = false))
        assertTrue(SiteCategory.ADULT.url in fetcher.fetched)
        assertNotNull(filterOf(service).blocks("https://adult.example/"))
    }

    @Test
    fun theFilterFileIsLoadedAtTheNextStart() = runBlocking {
        service().update(force = false)
        fetcher.fetched.clear()
        val again = service()
        assertNotNull(filterOf(again).blocks("https://adult.example/"))
        assertTrue(fetcher.fetched.isEmpty())
    }

    @Test
    fun aSiteAParentAllowedLoadsWithTheRequestsOfItsPages() = runBlocking {
        val service = service()
        service.update(force = false)
        val filter = filterOf(service)
        assertEquals("example.co.uk", filter.siteOf("bet.example.co.uk"))
        hosts.setSiteAllowed("example.co.uk", time, Long.MAX_VALUE)
        assertNull(filter.blocks("https://bet.example.co.uk/"))
        //a request of a blocked site on the page of an allowed one
        assertNull(filter.blocks("https://adult.example/img.png", false, "https://bet.example.co.uk/"))
        //but not its page
        assertNotNull(filter.blocks("https://adult.example/"))
    }

    @Test
    fun anHourEndsAndAClockTurnedBackEndsItToo() = runBlocking {
        val service = service()
        service.update(force = false)
        val filter = filterOf(service)
        val hour = 60 * 60_000L
        hosts.setSiteAllowed("casino.example", time, time + hour)
        assertNull(filter.blocks("https://casino.example/"))
        time += hour
        assertNotNull(filter.blocks("https://casino.example/"))
        time -= 2 * hour
        assertNotNull(filter.blocks("https://casino.example/"))
        //removed
        time += hour
        hosts.setSiteAllowed("casino.example", null, null)
        assertNotNull(filter.blocks("https://casino.example/"))
    }

    /** Another profile shown, or the kids profile off: no filter in memory, but its file stays */
    @Test
    fun theFilterIsInMemoryOnlyWhileTheKidsProfileIsShown() = runBlocking {
        val hidden = service(shown = false)
        hidden.update(force = false)
        assertNull(hidden.filter)
        assertTrue(File(dir, SiteFilterService.FILTER_FILE).exists())
        hidden.setShown(true).join()
        assertNotNull(filterOf(hidden).blocks("https://adult.example/"))
        hidden.setShown(false).join()
        assertNull(hidden.filter)
        //turned off: nothing wanted, the file kept for the next time
        wanted = SiteFilterService.Wanted.NONE
        hidden.onWantedChanged().join()
        assertEquals(0L, hidden.mask)
        assertTrue(File(dir, SiteFilterService.FILTER_FILE).exists())
        wanted = SiteFilterService.Wanted(SiteCategory.listed.toSet(), "")
        fetcher.fetched.clear()
        val again = service()
        again.onWantedChanged().join()
        assertNotNull(filterOf(again).blocks("https://adult.example/"))
        assertTrue(fetcher.fetched.isEmpty())
    }

    /** The downloaded lists are not kept: a list downloaded again replaces its own category in the filter */
    @Test
    fun aListUpdatedReplacesItsCategoryOnly() = runBlocking {
        val service = service()
        service.update(force = false)
        assertEquals(setOf(SiteFilterService.LISTS_FILE, SiteFilterService.FILTER_FILE), dir.list()!!.toSet())
        fetcher.lists[SiteCategory.ADULT.url!!] = "other.example\n"
        fetcher.failing += SiteCategory.GAMBLING.url!!
        time += 31 * 24 * 60 * 60_000L
        assertFalse(service.update(force = false))
        val filter = filterOf(service)
        assertNotNull(filter.blocks("https://other.example/"))
        assertNull(filter.blocks("https://adult.example/"))
        //its update failed: it blocks as before
        assertNotNull(filter.blocks("https://casino.example/"))
        assertNotNull(filterOf(service()).blocks("https://casino.example/"))
    }

    @Test
    fun theListsAreDownloadedAgainOnceAMonth() = runBlocking {
        val service = service()
        service.update(force = false)
        fetcher.fetched.clear()
        time += 7 * 24 * 60 * 60_000L
        service.update(force = false)
        assertTrue(fetcher.fetched.isEmpty())
        time += 23 * 24 * 60 * 60_000L
        service.update(force = false)
        assertEquals(4, fetcher.fetched.size)
    }
}
