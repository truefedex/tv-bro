package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.webengine.SiteView
import android.graphics.Bitmap
import android.os.Looper
import androidx.room.Room
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.webengine.DataClearing
import com.phlox.tvwebbrowser.webengine.DataProfile
import com.phlox.tvwebbrowser.webengine.PageZoom
import com.phlox.tvwebbrowser.webengine.SiteDataKind
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class BrowserControllerEventsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val config get() = AppContext.provideConfig()
    private lateinit var db: AppDatabase
    private lateinit var controller: BrowserController
    private lateinit var host: FakeTabViewHost
    private val events = ArrayList<BrowserUiEvent>()
    private val eventsScope = CoroutineScope(Dispatchers.Unconfined)

    /** Waits for the history writes: they are delayed by the logger and done on Room threads */
    private fun storedHistory(expectedSize: Int): List<String> {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VisitedHistoryLogger.MIN_VISITED_INTERVAL + 100))
        val deadline = System.currentTimeMillis() + 5000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val rows = db.query("SELECT title, url FROM history ORDER BY id", null).use { c ->
                generateSequence { if (c.moveToNext()) "${c.getString(0)} ${c.getString(1)}" else null }.toList()
            }
            if (rows.size >= expectedSize || System.currentTimeMillis() > deadline) {
                //let the logger finish (it marks the item as saved on the main thread)
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
                return rows
            }
            Thread.sleep(5)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase.setInstanceForTests(db)
        WebEngineFactory.initializeForTests(FakeWebEngine.provider)
        controller = BrowserController()
        host = FakeTabViewHost(context)
        controller.viewHost = host
        eventsScope.launch { controller.uiEvents.collect { events += it } }
    }

    @After
    fun tearDown() {
        eventsScope.cancel()
        val container = TVBro.container
        val job = container.appScope.launch { container.tabWrites.awaitPending() }
        while (!job.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        controller.clear()
        AppDatabase.setInstanceForTests(null)
        db.close()
    }

    private fun awaitWrite(job: Job?) {
        while (job != null && !job.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    private fun open(url: String): BrowserTab =
        controller.openInNewTab(url, controller.tabsStates.size, navigateImmediately = true)!!

    private val BrowserTab.callback: WebEngineCallbacks get() = fakeEngine.callback!!

    /** What an engine does when the process that draws its view dies: it drops the view */
    private fun loseView(tab: BrowserTab, callback: WebEngineCallbacks) {
        tab.fakeEngine.engineView = null
        tab.fakeEngine.callback = null
        tab.fakeEngine.log.clear()
        callback.onEngineViewLost()
    }
    private val state get() = controller.currentTabState.value

    @Test
    fun stateFollowsTheCurrentTab() {
        assertFalse(state.hasTab)
        val a = open("https://a.com/")
        a.fakeEngine.canGoBackValue = true
        val b = open("https://b.com/")
        assertEquals("https://b.com/", state.url)
        assertFalse(state.canGoBack)

        controller.changeTab(a)
        assertTrue(state.hasTab)
        assertEquals("https://a.com/", state.url)
        assertTrue(state.canGoBack)

        controller.closeTab(a)
        assertSame(b, controller.currentTab.value)
        assertEquals("https://b.com/", state.url)
    }

    @Test
    fun pageStartUpdatesTheUrlAndResetsCounters() {
        val tab = open("https://a.com/")
        tab.blockedAds = 3
        tab.blockedPopups = 2
        tab.fakeEngine.loadedUrls += "https://a.com/page"
        tab.callback.onPageStarted("https://ignored.com/")
        assertEquals("https://a.com/page", tab.url)
        assertEquals("https://a.com/page", state.url)
        assertEquals(0, state.blockedAds)
        assertEquals(0, state.blockedPopups)
    }

    private fun download(tab: BrowserTab, url: String) =
        tab.callback.onDownloadRequested(com.phlox.tvwebbrowser.webengine.DownloadRequest(url, referer = "", userAgent = null))

    @Test
    fun aTabOpenedForADownloadDoesNotKeepItsLink() {
        //the link redirects to the file: the download has another url
        val tab = open("https://a.com/archive.zip")
        assertEquals("https://a.com/archive.zip", tab.url)
        download(tab, "https://files.a.com/archive.zip")
        assertEquals(BrowserController.BLANK_URL, tab.url)
        assertEquals(BrowserController.BLANK_URL, state.url)
        //the UI still gets the download
        assertTrue("onDownloadRequested" in host.uiHostCalls)
    }

    @Test
    fun aDownloadTypedOnAPageKeepsThePage() {
        val tab = open("https://a.com/")
        tab.callback.onPageStarted("https://a.com/")
        controller.navigate("https://b.com/file.zip")
        assertEquals("https://b.com/file.zip", tab.url)
        download(tab, "https://b.com/file.zip")
        assertEquals("https://a.com/", tab.url)
        assertEquals("https://a.com/", state.url)
    }

    @Test
    fun aDownloadOfALinkOfThePageChangesNothing() {
        val tab = open("https://a.com/")
        tab.fakeEngine.loadedUrls += "https://a.com/page"
        tab.callback.onPageStarted("https://a.com/page")
        download(tab, "https://a.com/file.zip")
        assertEquals("https://a.com/page", tab.url)
    }

    @Test
    fun currentTabGetsANewViewWhenItsViewIsLost() {
        val tab = open("https://a.com/")
        val loaded = tab.fakeEngine.loadedUrls.toList()
        loseView(tab, tab.callback)
        assertTrue(tab.fakeEngine.engineView != null)
        assertEquals(listOf("attach"), tab.fakeEngine.log)
        assertSame(host.tabsContainer, tab.fakeEngine.parent)
        //the engine shows what it shows after such a loss: the core neither restores nor loads a page
        assertEquals(loaded, tab.fakeEngine.loadedUrls)
        assertTrue(state.hasTab)
    }

    @Test
    fun backgroundTabGetsItsNewViewWhenShown() {
        val a = open("https://a.com/")
        val aCallback = a.callback
        open("https://b.com/")
        loseView(a, aCallback)
        assertNull(a.fakeEngine.engineView)
        assertTrue(a.fakeEngine.log.isEmpty())

        controller.changeTab(a)
        assertTrue(a.fakeEngine.engineView != null)
        assertTrue(a.fakeEngine.log.contains("attach"))
    }

    @Test
    fun pageFinishUpdatesTheUrl() {
        val tab = open("https://a.com/")
        tab.fakeEngine.loadedUrls += "https://a.com/redirected"
        tab.callback.onPageFinished("https://a.com/")
        assertEquals("https://a.com/redirected", state.url)
    }

    @Test
    fun blockedAdsAreCountedWhenAdBlockingIsEnabled() {
        config.adBlockEnabled = true
        val tab = open("https://a.com/")
        tab.callback.onBlockedAd("https://ads.com/1")
        tab.callback.onBlockedAd("https://ads.com/2")
        assertEquals(2, state.blockedAds)

        config.adBlockEnabled = false
        tab.callback.onBlockedAd("https://ads.com/3")
        assertEquals(2, tab.blockedAds)
    }

    @Test
    fun blockedAdsFollowTheSettingOfTheSiteNotTheGlobalOne() {
        config.adBlockEnabled = false
        val tab = open("https://a.com/")
        awaitWrite(controller.setSiteAdBlock(tab, true))
        tab.callback.onBlockedAd("https://ads.com/1")
        assertEquals(1, state.blockedAds)
    }

    @Test
    fun stateHasTheSiteAndItsPopupBlocking() {
        val tab = open("https://a.com/page")
        assertEquals("a.com", state.host)
        assertEquals(HostConfig.DEFAULT_BLOCK_POPUPS_VALUE, state.popupBlockLevel)
        runBlocking { controller.changePopupBlockingLevel(HostConfig.POPUP_BLOCK_NONE, tab) }
        assertEquals(HostConfig.POPUP_BLOCK_NONE, state.popupBlockLevel)
    }

    @Test
    fun homePageSearchBarIsReportedToTheUi() {
        val tab = open(Config.HOME_URL_ALIAS)
        tab.callback.onHomePageSearchBarLocated(10, 20)
        assertEquals(BrowserUiEvent.HomePageSearchBarLocated(tab, 10, 20), events.last())
    }

    @Test
    fun pageWithoutHostHasNoSite() {
        open(Config.HOME_URL_ALIAS)
        assertEquals("", state.host)
    }

    @Test
    fun pageLoadingIsShownFromStartToFinish() {
        val tab = open("https://a.com/")
        assertFalse(state.loading)
        tab.callback.onPageStarted("https://a.com/")
        assertTrue(state.loading)
        tab.callback.onPageFinished("https://a.com/")
        assertFalse(state.loading)
    }

    @Test
    fun progressShowsALoadThatStartedBeforeTheTabWasShown() {
        val tab = open("https://a.com/")
        tab.callback.onProgressChanged(40)
        assertTrue(state.loading)
        tab.callback.onProgressChanged(100)
        assertFalse(state.loading)
    }

    @Test
    fun stopEndsTheLoading() {
        val tab = open("https://a.com/")
        tab.callback.onPageStarted("https://a.com/")
        controller.stopLoading()
        assertEquals("stopLoading", tab.fakeEngine.log.last())
        assertFalse(state.loading)
    }

    @Test
    fun loadingOfATabEndsWhenItIsNoLongerShown() {
        val a = open("https://a.com/")
        a.callback.onPageStarted("https://a.com/")
        open("https://b.com/")
        assertFalse(state.loading)
        assertFalse(a.loading)
    }

    @Test
    fun blockedDialogIsCountedAndReported() {
        val tab = open("https://a.com/")
        events.clear()
        tab.callback.onBlockedDialog(false)
        assertEquals(1, state.blockedPopups)
        assertEquals(listOf(BrowserUiEvent.PopupBlocked(false)), events)
    }

    @Test
    fun connectionFollowsTheScheme() {
        val tab = open("https://a.com/")
        assertEquals(SiteConnection.SECURE, state.connection)
        controller.navigate("http://b.com/")
        tab.callback.onPageStarted("http://b.com/")
        assertEquals(SiteConnection.HTTP, state.connection)
        controller.navigate(Config.HOME_URL_ALIAS)
        tab.callback.onPageStarted(Config.HOME_URL_ALIAS)
        assertEquals(SiteConnection.NONE, state.connection)
    }

    @Test
    fun certificateErrorStaysWithItsHostUntilNavigation() {
        val tab = open("https://a.com/")
        tab.callback.onPageCertificateError("https://a.com/")
        assertEquals(SiteConnection.CERTIFICATE_ERROR, state.connection)
        //the warning page, then the page itself after the user went on
        tab.callback.onPageStarted(null)
        assertEquals(SiteConnection.CERTIFICATE_ERROR, state.connection)
        controller.navigate("https://a.com/other")
        assertEquals(SiteConnection.SECURE, state.connection)
    }

    @Test
    fun certificateErrorEndsOnAnotherHost() {
        val tab = open("https://a.com/")
        tab.callback.onPageCertificateError("https://a.com/")
        //a link of the page
        tab.fakeEngine.loadedUrls += "https://b.com/"
        tab.callback.onPageStarted("https://b.com/")
        assertEquals(SiteConnection.SECURE, state.connection)
    }

    @Test
    fun certificateErrorBelongsToItsTab() {
        val a = open("https://a.com/")
        a.callback.onPageCertificateError("https://a.com/")
        open("https://b.com/")
        assertEquals(SiteConnection.SECURE, state.connection)
        controller.changeTab(a)
        assertEquals(SiteConnection.CERTIFICATE_ERROR, state.connection)
    }

    @Test
    fun siteViewFollowsTheInterfaceByDefault() {
        controller.interfaceSiteView = SiteView.MOBILE
        val tab = open("https://a.com/")
        assertEquals(SiteView.MOBILE, tab.fakeEngine.view)
        assertEquals(SiteView.MOBILE, state.siteView)
        assertTrue(state.siteViewIsDefault)
        controller.interfaceSiteView = SiteView.TABLET
        controller.navigate("https://b.com/")
        assertEquals(SiteView.TABLET, tab.fakeEngine.view)
    }

    @Test
    fun hostsCanHaveEachOfTheThreeViews() {
        val tab = open("https://a.com/")
        assertEquals(SiteView.TABLET, state.siteView)
        awaitWrite(controller.setSiteView(tab, SiteView.DESKTOP))
        assertEquals(SiteView.DESKTOP, tab.fakeEngine.view)
        assertEquals(SiteView.DESKTOP, state.siteView)
        assertEquals(SiteView.DESKTOP.code, runBlocking { db.hostsDao().findByHostName("a.com") }!!.siteView)
        awaitWrite(controller.setSiteView(tab, SiteView.MOBILE))
        assertEquals(SiteView.MOBILE, tab.fakeEngine.view)
        assertEquals(SiteView.MOBILE.code, runBlocking { db.hostsDao().findByHostName("a.com") }!!.siteView)
    }

    @Test
    fun defaultSiteViewOfOlderVersionsIsKept() {
        config.prefs.edit().putBoolean(Config.LEGACY_DEFAULT_SITE_VIEW_KEY, true).commit()
        assertEquals(SiteView.DESKTOP, config.defaultSiteView)
        config.prefs.edit().putBoolean(Config.LEGACY_DEFAULT_SITE_VIEW_KEY, false).commit()
        assertEquals(SiteView.MOBILE, config.defaultSiteView)
        config.defaultSiteView = SiteView.TABLET
        assertEquals(SiteView.TABLET, config.defaultSiteView)
        assertFalse(config.prefs.contains(Config.LEGACY_DEFAULT_SITE_VIEW_KEY))
        config.defaultSiteView = null
        assertNull(config.defaultSiteView)
    }

    @Test
    fun siteViewOfAHostIsKeptOnlyWhileItDiffersFromTheDefault() {
        val tab = open("https://a.com/")
        assertEquals(SiteView.TABLET, tab.fakeEngine.view)
        tab.fakeEngine.log.clear()
        awaitWrite(controller.setSiteView(tab, SiteView.MOBILE))
        assertEquals(SiteView.MOBILE, tab.fakeEngine.view)
        assertEquals(listOf("reload"), tab.fakeEngine.log)
        assertEquals(SiteView.MOBILE, state.siteView)
        assertFalse(state.siteViewIsDefault)
        assertEquals(SiteView.MOBILE.code, runBlocking { db.hostsDao().findByHostName("a.com") }!!.siteView)

        awaitWrite(controller.setSiteView(tab, SiteView.TABLET))
        assertEquals(SiteView.TABLET, tab.fakeEngine.view)
        assertTrue(state.siteViewIsDefault)
        assertNull(runBlocking { db.hostsDao().findByHostName("a.com") }!!.siteView)
    }

    @Test
    fun windowTabStartsInTheViewOfItsOpener() {
        //its first page, mostly of the opener's site, then does not start again in another view
        val a = open("https://a.com/")
        awaitWrite(controller.setSiteView(a, SiteView.DESKTOP))
        a.callback.onCreateWindow(dialog = false, userGesture = true)
        val windowTab = controller.tabsStates[1]
        assertEquals("", windowTab.url)
        assertEquals(SiteView.DESKTOP, windowTab.fakeEngine.view)
    }

    /** Runs the main looper until the page zoom of the [host] is written */
    private fun awaitPageZoom(host: String, zoom: Int?) {
        val deadline = System.currentTimeMillis() + 5000
        while (runBlocking { db.hostsDao().findByHostName(host) }?.pageZoom != zoom) {
            assertTrue("page zoom $zoom of $host not written", System.currentTimeMillis() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    @Test
    fun zoomOutGoesBelowTheWidthOfThePageWithThePageZoomOfItsHost() {
        val tab = open("https://a.com/page")
        val told = ArrayList<Int>()
        //zoomed in: the engine zooms out first, and tells how far it is zoomed in then
        tab.fakeEngine.zoomedIn = true
        tab.fakeEngine.visualZoom = 1.25
        controller.zoomOut(tab) { told += it }
        assertEquals(listOf("zoomOut"), tab.fakeEngine.log.filter { it.startsWith("zoom") })
        assertEquals(listOf(125), told)
        told.clear()
        //at the width of the page: the page zoom of the host steps down
        tab.fakeEngine.zoomedIn = false
        controller.zoomOut(tab) { told += it }
        controller.zoomOut(tab) { told += it }
        assertEquals(listOf(90, 80), told)
        assertEquals(listOf(90, 80), tab.fakeEngine.pageZooms)
        awaitPageZoom("a.com", 80)
        //other pages of the host ask for it
        assertEquals(80, WebEngineFactory.pageZooms?.zoomOf("https://a.com/other"))
        assertEquals(PageZoom.DEFAULT, WebEngineFactory.pageZooms?.zoomOf("https://b.com/"))
        //the smallest stays and is told again
        repeat(PageZoom.STEPS.size) { controller.zoomOut(tab) { told += it } }
        assertEquals(PageZoom.STEPS.first(), told.last())
        assertEquals(PageZoom.STEPS.first(), tab.fakeEngine.pageZooms.last())
    }

    @Test
    fun zoomInGivesThePageItsWidthBackBeforeTheEngineZoomsIn() {
        val tab = open("https://a.com/")
        val told = ArrayList<Int>()
        controller.zoomOut(tab) { told += it }
        awaitPageZoom("a.com", 90)
        tab.fakeEngine.log.clear()
        controller.zoomIn(tab) { told += it }
        assertEquals(listOf(90, 100), told)
        assertEquals(listOf(90, 100), tab.fakeEngine.pageZooms)
        //100% is no setting of the host
        awaitPageZoom("a.com", null)
        assertEquals(emptyList<String>(), tab.fakeEngine.log.filter { it.startsWith("zoom") })
        //at 100% the engine zooms in and tells its zoom
        tab.fakeEngine.visualZoom = 1.5
        controller.zoomIn(tab) { told += it }
        assertEquals(listOf("zoomIn"), tab.fakeEngine.log.filter { it.startsWith("zoom") })
        assertEquals(listOf(90, 100, 150), told)
        //a page that does not tell its zoom: nothing is told
        tab.fakeEngine.visualZoom = null
        controller.zoomIn(tab) { told += it }
        assertEquals(listOf(90, 100, 150), told)
    }

    @Test
    fun aPinchOfAZoomedOutPageIsToldWithThePageZoom() {
        val tab = open("https://a.com/")
        val told = ArrayList<Int>()
        controller.zoomOut(tab) { told += it }
        controller.zoomOut(tab) { told += it }
        awaitPageZoom("a.com", 80)
        //zoomed in at 80% (with fingers, on a phone): zooming out tells the zoom of both
        tab.fakeEngine.zoomedIn = true
        tab.fakeEngine.visualZoom = 1.5
        controller.zoomOut(tab) { told += it }
        assertEquals(listOf(90, 80, 120), told)
    }

    @Test
    fun theHomePageHasNoPageZoom() {
        val tab = open(Config.HOME_PAGE_URL)
        controller.zoomOut(tab) { fail("the home page has no page zoom") }
        assertEquals(listOf("zoomOut"), tab.fakeEngine.log.filter { it.startsWith("zoom") })
        assertEquals(emptyList<Int>(), tab.fakeEngine.pageZooms)
    }

    @Test
    fun qwantGetsTheMobileViewUntilTheUserChoosesAnother() {
        val tab = open("https://www.qwant.com/?q=tv")
        assertEquals(SiteView.MOBILE, tab.fakeEngine.view)
        assertEquals(SiteView.MOBILE, state.siteView)
        assertTrue(state.siteViewIsDefault)
        awaitWrite(controller.setSiteView(tab, SiteView.DESKTOP))
        assertEquals(SiteView.DESKTOP, tab.fakeEngine.view)
        assertFalse(state.siteViewIsDefault)
        assertEquals(SiteView.DESKTOP.code, runBlocking { db.hostsDao().findByHostName("www.qwant.com") }!!.siteView)
        //back to the view of the list: no setting of its own
        awaitWrite(controller.setSiteView(tab, SiteView.MOBILE))
        assertEquals(SiteView.MOBILE, tab.fakeEngine.view)
        assertTrue(state.siteViewIsDefault)
    }

    @Test
    fun theListOfSiteViewsTakesSubdomains() {
        assertEquals(SiteView.MOBILE, SiteViewDefaults.siteView("qwant.com"))
        assertEquals(SiteView.MOBILE, SiteViewDefaults.siteView("WWW.Qwant.com."))
        assertNull(SiteViewDefaults.siteView("notqwant.com"))
        assertNull(SiteViewDefaults.siteView("qwant.com.example.org"))
        assertNull(SiteViewDefaults.siteView("com"))
    }

    @Test
    fun defaultSiteViewSettingWinsOverTheInterface() {
        val tab = open("https://a.com/")
        assertEquals(SiteView.TABLET, tab.fakeEngine.view)
        tab.fakeEngine.log.clear()
        config.defaultSiteView = SiteView.MOBILE
        controller.onSiteViewSettingsChanged()
        assertEquals(SiteView.MOBILE, tab.fakeEngine.view)
        assertEquals(listOf("reload"), tab.fakeEngine.log)
        assertEquals(SiteView.MOBILE, state.siteView)
        config.defaultSiteView = null
    }

    @Test
    fun userAgentSettingReachesAllTabsAndReloadsTheCurrentOne() {
        val first = open("https://a.com/")
        val current = open("https://b.com/")
        assertNull(current.fakeEngine.userAgentOverride)
        first.fakeEngine.log.clear()
        current.fakeEngine.log.clear()
        config.userAgent = "MyAgent/1.0"
        try {
            controller.onSiteViewSettingsChanged()
            assertEquals("MyAgent/1.0", current.fakeEngine.userAgentOverride)
            assertEquals("MyAgent/1.0", first.fakeEngine.userAgentOverride)
            assertEquals(listOf("reload"), current.fakeEngine.log)
            assertFalse("reload" in first.fakeEngine.log)
            //the site view stays
            assertEquals(SiteView.TABLET, current.fakeEngine.view)
        } finally {
            config.userAgent = null
        }
        controller.onSiteViewSettingsChanged()
        assertNull(current.fakeEngine.userAgentOverride)
    }

    @Test
    fun blankAndLegacyUserAgentsAreAuto() {
        config.userAgent = "  "
        assertNull(config.userAgent)
        config.prefs.edit().putString(Config.USER_AGENT_KEY, "TV Bro/1.0 Mozilla/5.0").commit()
        assertNull(config.userAgent)
        config.userAgent = null
    }

    @Test
    fun clearingSiteDataClearsTheHostAndReloads() {
        FakeWebEngine.clearedSites.clear()
        FakeWebEngine.clearedKinds.clear()
        val tab = open("https://www.a.com/page")
        runBlocking { db.hostsDao().setSiteView("www.a.com", SiteView.MOBILE.code) }
        tab.fakeEngine.log.clear()
        runBlocking { controller.clearSiteData(tab) }
        assertEquals(listOf("www.a.com"), FakeWebEngine.clearedSites)
        assertEquals(listOf(SiteDataKind.ALL), FakeWebEngine.clearedKinds)
        assertEquals(listOf("reload"), tab.fakeEngine.log)
        assertTrue(state.siteDataClearable)
        //the settings of the site stay
        assertEquals(SiteView.MOBILE.code, runBlocking { db.hostsDao().findByHostName("www.a.com") }!!.siteView)
    }

    @Test
    fun clearingChosenSiteDataClearsOnlyThem() {
        FakeWebEngine.clearedSites.clear()
        FakeWebEngine.clearedKinds.clear()
        val tab = open("https://www.a.com/page")
        tab.fakeEngine.log.clear()
        val kinds = setOf(SiteDataKind.STORAGE, SiteDataKind.CACHE)
        runBlocking { controller.clearSiteData(tab, kinds) }
        assertEquals(listOf(kinds), FakeWebEngine.clearedKinds)
        assertEquals(listOf("reload"), tab.fakeEngine.log)
        //nothing chosen: nothing cleared, no reload
        tab.fakeEngine.log.clear()
        runBlocking { controller.clearSiteData(tab, emptySet()) }
        assertEquals(1, FakeWebEngine.clearedKinds.size)
        assertTrue(tab.fakeEngine.log.isEmpty())
    }

    @Test
    fun clearingBrowsingDataClearsTheChosenKinds() {
        FakeWebEngine.clearedBrowsingData.clear()
        FakeWebEngine.dataClearing.clear()
        val tab = open("https://www.a.com/page")
        runBlocking {
            db.historyDao().insert(com.phlox.tvwebbrowser.model.HistoryItem().apply {
                url = "https://www.b.com/"
                title = "B"
                time = 1000
            })
        }
        tab.fakeEngine.log.clear()
        //history and cache: the cookies stay, so the page does not reload
        runBlocking {
            controller.clearBrowsingData(mapOf(DataProfile.MAIN to setOf(BrowsingDataKind.HISTORY, BrowsingDataKind.CACHE)),
                DataProfile.MAIN)
        }
        assertEquals(0, runBlocking { db.historyDao().count() })
        assertEquals(listOf(DataProfile.MAIN to setOf(SiteDataKind.CACHE)), FakeWebEngine.clearedBrowsingData)
        assertTrue(tab.fakeEngine.log.isEmpty())

        //the cookies of another profile: the page shown stays
        runBlocking { controller.clearBrowsingData(mapOf(DataProfile.KIDS to setOf(BrowsingDataKind.SITE_DATA)), DataProfile.MAIN) }
        assertEquals(DataProfile.KIDS to setOf(SiteDataKind.COOKIES, SiteDataKind.STORAGE), FakeWebEngine.clearedBrowsingData.last())
        assertTrue(tab.fakeEngine.log.isEmpty())

        //those of its own profile: the page reloads to show the user signed out
        runBlocking { controller.clearBrowsingData(mapOf(DataProfile.MAIN to setOf(BrowsingDataKind.SITE_DATA)), DataProfile.MAIN) }
        assertEquals(listOf("reload"), tab.fakeEngine.log)
    }

    @Test
    fun clearingTheAppsOwnDataOfTheProfileShown() {
        //past the first start, which fills the empty home page with suggestions
        config.initialBookmarksSuggestionsLoaded = true
        open("https://www.a.com/page")
        val tab = open("https://www.b.com/page")
        runBlocking {
            db.favoritesDao().insert(com.phlox.tvwebbrowser.model.FavoriteItem().apply {
                title = "A"
                url = "https://www.a.com/"
            })
            db.hostsDao().setSiteView("www.b.com", SiteView.MOBILE.code)
            db.hostsDao().setFavicon("www.b.com", "b.png")
            fun download(status: Int, kids: Boolean) = com.phlox.tvwebbrowser.model.Download().apply {
                url = "https://www.a.com/file"
                filename = "file"
                this.status = status
                this.kids = kids
            }
            db.downloadDao().insert(download(com.phlox.tvwebbrowser.model.Download.Status.DONE, kids = false))
            db.downloadDao().insert(download(com.phlox.tvwebbrowser.model.Download.Status.PAUSED, kids = false))
            db.downloadDao().insert(download(com.phlox.tvwebbrowser.model.Download.Status.DONE, kids = true))
        }
        tab.fakeEngine.log.clear()
        runBlocking {
            controller.clearBrowsingData(mapOf(DataProfile.MAIN to setOf(BrowsingDataKind.BOOKMARKS,
                BrowsingDataKind.DOWNLOADS, BrowsingDataKind.SITE_SETTINGS)), DataProfile.MAIN)
        }
        assertTrue(runBlocking { db.favoritesDao().getBookmarks() }.isEmpty())
        //the site settings go, the icon stays; the page reloads in the default view
        val host = runBlocking { db.hostsDao().findByHostName("www.b.com") }!!
        assertNull(host.siteView)
        assertEquals("b.png", host.favicon)
        assertEquals(listOf("reload"), tab.fakeEngine.log)
        //only the ended downloads of the main profile go
        val downloads = runBlocking { db.downloadDao().observeAll().first() }
        assertEquals(2, downloads.size)
        assertTrue(downloads.any { it.kids } && downloads.any { it.status == com.phlox.tvwebbrowser.model.Download.Status.PAUSED })

        //the tabs shown: closed, and a new one with the home page
        runBlocking { controller.clearBrowsingData(mapOf(DataProfile.MAIN to setOf(BrowsingDataKind.TABS)), DataProfile.MAIN) }
        assertEquals(1, controller.tabsStates.size)
        assertEquals(config.homePage, controller.tabsStates[0].url)
        assertSame(controller.tabsStates[0], controller.currentTab.value)
    }

    @Test
    fun profilesClearedTogetherAreClearedOnce() {
        FakeWebEngine.clearedBrowsingData.clear()
        FakeWebEngine.dataClearing.clear()
        FakeWebEngine.dataClearing[DataProfile.MAIN] = DataClearing.WITH_OTHERS
        FakeWebEngine.dataClearing[DataProfile.KIDS] = DataClearing.WITH_OTHERS
        FakeWebEngine.dataClearing[DataProfile.INCOGNITO] = DataClearing.NONE
        try {
            val tab = open("https://www.a.com/page")
            tab.fakeEngine.log.clear()
            val site = setOf(BrowsingDataKind.SITE_DATA)
            //the kids column clears the main profile too, so the page shown reloads
            runBlocking {
                controller.clearBrowsingData(mapOf(DataProfile.KIDS to site, DataProfile.MAIN to site,
                    DataProfile.INCOGNITO to site), DataProfile.MAIN)
            }
            assertEquals(1, FakeWebEngine.clearedBrowsingData.size)
            assertEquals(listOf("reload"), tab.fakeEngine.log)
        } finally {
            FakeWebEngine.dataClearing.clear()
        }
    }

    @Test
    fun pageInTheViewOfAnotherHostLoadsAgainOnce() {
        val tab = open("https://a.com/")
        awaitWrite(controller.setSiteView(tab, SiteView.MOBILE))
        //going back to a page of another host, which the engine did not switch the view for
        tab.fakeEngine.loadedUrls += "https://b.com/"
        tab.fakeEngine.log.clear()
        tab.callback.onPageStarted("https://b.com/")
        assertEquals(SiteView.TABLET, tab.fakeEngine.view)
        assertEquals(listOf("reload"), tab.fakeEngine.log)
        //the page loaded again redirects back by its view: no second reload
        tab.fakeEngine.loadedUrls += "https://a.com/"
        tab.callback.onPageStarted("https://a.com/")
        assertEquals(SiteView.TABLET, tab.fakeEngine.view)
        assertEquals(listOf("reload"), tab.fakeEngine.log)
    }

    @Test
    fun pageStartingInTheViewOfAnotherHostBeforeItCommitsIsLoadedByItsUrl() {
        val tab = open("https://a.com/")
        awaitWrite(controller.setSiteView(tab, SiteView.DESKTOP))
        //a redirect to another host (Google's /url? links): WebView still tells the page before it as its url,
        //so a reload would load that one again in place of the redirect's target
        tab.fakeEngine.log.clear()
        tab.callback.onPageStarted("https://b.com/")
        assertEquals(SiteView.TABLET, tab.fakeEngine.view)
        assertEquals(emptyList<String>(), tab.fakeEngine.log)
        assertEquals("https://b.com/", tab.fakeEngine.loadedUrls.last())
        //the page loaded by its url starts in its own view: nothing more
        tab.callback.onPageStarted("https://b.com/")
        assertEquals(listOf("https://a.com/", "https://b.com/"), tab.fakeEngine.loadedUrls.takeLast(2))
        assertEquals(emptyList<String>(), tab.fakeEngine.log)
    }

    @Test
    fun aPageOfAnotherViewStartingBeforeItCommitsIsNotLoadedAgain() {
        val tab = open(Config.HOME_PAGE_URL)
        assertEquals(SiteView.TABLET, tab.fakeEngine.view)
        //the home page searches Qwant: GeckoView switched the view for it and still tells the home page as its url
        tab.fakeEngine.view = SiteView.MOBILE
        tab.fakeEngine.log.clear()
        tab.callback.onPageStarted("https://www.qwant.com/?q=tv")
        assertEquals(SiteView.MOBILE, tab.fakeEngine.view)
        assertEquals(emptyList<String>(), tab.fakeEngine.log)
    }

    @Test
    fun titleUpdatesTheTabAndTheHistory() {
        val tab = open("https://a.com/")
        events.clear()
        tab.callback.onReceivedTitle("Title A")
        assertEquals("Title A", tab.title)
        assertEquals("Title A", state.title)
        assertEquals(listOf<BrowserUiEvent>(BrowserUiEvent.TabUpdated(tab)), events)
    }

    @Test
    fun titleOfASavedVisitIsUpdated() {
        val tab = open("https://a.com/")
        tab.title = "Loading"
        tab.callback.onVisited("https://a.com/")
        assertEquals(listOf("Loading https://a.com/"), storedHistory(1))
        tab.callback.onReceivedTitle("Title A")
        val deadline = System.currentTimeMillis() + 5000
        var rows = storedHistory(1)
        while (rows != listOf("Title A https://a.com/") && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            rows = storedHistory(1)
        }
        assertEquals(listOf("Title A https://a.com/"), rows)
    }

    @Test
    fun visitsGetTheTitlesOfTheirOwnPages() {
        val tab = open("https://a.com/")
        tab.callback.onPageStarted("https://a.com/")
        tab.callback.onVisited("https://a.com/")
        tab.callback.onReceivedTitle("A")
        tab.callback.onPageFinished("https://a.com/")
        assertEquals(listOf("A https://a.com/"), storedHistory(1))
        //the page changes its url itself (history.pushState): the engine reports no page load
        tab.fakeEngine.loadedUrls += "https://a.com/watch"
        tab.callback.onVisited("https://a.com/watch")
        tab.callback.onReceivedTitle("Video")
        assertEquals(listOf("A https://a.com/", "Video https://a.com/watch"), storedHistory(2))
    }

    @Test
    fun visitOfAPageStillLoadingDoesNotTakeTheTitleOfThePreviousOne() {
        val tab = open("https://a.com/")
        tab.callback.onReceivedTitle("A")
        tab.fakeEngine.loadedUrls += "https://b.com/"
        tab.callback.onPageStarted("https://b.com/")
        tab.callback.onVisited("https://b.com/")
        assertEquals(listOf(" https://b.com/"), storedHistory(1))
    }

    @Test
    fun iconIsStoredForTheHostOfThePageShownNow() {
        val stored = ConcurrentHashMap<String, String>()
        val originalDelegate = FaviconsPool.databaseDelegate
        val originalFetcher = FaviconsPool.fetcher
        FaviconsPool.clear()
        FaviconsPool.databaseDelegate = object : FaviconsPool.DatabaseDelegate {
            override suspend fun setFavicon(host: String, fileName: String) { stored[host] = fileName }
        }
        //the page has no icon of its own: the one of the engine is kept
        FaviconsPool.fetcher = FaviconsPool.Fetcher { null }
        try {
            val tab = open("https://a.com/")
            //a redirect or history.pushState to another host: tab.url stays
            tab.fakeEngine.loadedUrls += "https://www.a.com/"
            tab.callback.onReceivedIcon(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
            val deadline = System.currentTimeMillis() + 5000
            while (stored.isEmpty() && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertEquals(setOf("www.a.com"), stored.keys)
        } finally {
            FaviconsPool.databaseDelegate = originalDelegate
            FaviconsPool.fetcher = originalFetcher
            FaviconsPool.clear()
        }
    }

    @Test
    fun progressIsReported() {
        val tab = open("https://a.com/")
        events.clear()
        tab.callback.onProgressChanged(42)
        assertEquals(listOf<BrowserUiEvent>(BrowserUiEvent.Progress(42)), events)
    }

    @Test
    fun windowsWithoutUserGestureAreBlocked() {
        val tab = open("https://a.com/")
        events.clear()
        assertNull(tab.callback.onCreateWindow(dialog = false, userGesture = false))
        assertEquals(1, controller.tabsStates.size)
        assertEquals(1, state.blockedPopups)
        assertEquals(listOf<BrowserUiEvent>(BrowserUiEvent.PopupBlocked(true)), events)
    }

    @Test
    fun windowsOpenedByUserGestureGetTheirTab() {
        val a = open("https://a.com/")
        val b = open("https://b.com/")
        controller.changeTab(a)
        val view = a.callback.onCreateWindow(dialog = false, userGesture = true)
        val windowTab = controller.tabsStates[1]
        assertSame(view, windowTab.fakeEngine.engineView)
        assertEquals(listOf(a, windowTab, b), controller.tabsStates.toList())
    }

    @Test
    fun blockedPopupPageClosesItsTabAndShowsTheOpener() {
        val a = open("https://a.com/")
        val b = open("https://b.com/")
        controller.changeTab(a)
        a.callback.onCreateWindow(dialog = false, userGesture = true)
        val windowTab = controller.tabsStates[1]
        assertSame(windowTab, controller.currentTab.value)
        events.clear()
        windowTab.callback.onPopupPageBlocked()
        assertEquals(listOf(a, b), controller.tabsStates.toList())
        assertSame(a, controller.currentTab.value)
        assertEquals(1, state.blockedPopups)
        assertEquals(listOf<BrowserUiEvent>(BrowserUiEvent.PopupBlocked(true)), events.filterIsInstance<BrowserUiEvent.PopupBlocked>())
    }

    @Test
    fun blockedPopupOfAClosedOpenerShowsTheNextTab() {
        val a = open("https://a.com/")
        val b = open("https://b.com/")
        controller.changeTab(a)
        a.callback.onOpenInNewTabRequested("about:blank", navigateImmediately = false)
        val popup = controller.tabsStates[1]
        controller.closeTab(a)
        assertNull(popup.openerTab)
        controller.changeTab(popup)
        popup.callback.onPopupPageBlocked()
        assertEquals(listOf(b), controller.tabsStates.toList())
        assertSame(b, controller.currentTab.value)
    }

    @Test
    fun pageCanOpenATabAfterItsOwn() {
        val a = open("https://a.com/")
        val b = open("https://b.com/")
        controller.changeTab(a)
        events.clear()
        val engine = a.callback.onOpenInNewTabRequested("https://new.com/", navigateImmediately = true)
        val newTab = controller.tabsStates[1]
        assertSame(newTab.webEngine, engine)
        assertEquals(listOf(a, newTab, b), controller.tabsStates.toList())
        assertEquals("https://new.com/", newTab.fakeEngine.url)
        assertEquals(listOf<BrowserUiEvent>(BrowserUiEvent.TabOpenedByPage), events)
    }

    @Test
    fun pageCanCloseItsTab() {
        val a = open("https://a.com/")
        val b = open("https://b.com/")
        events.clear()
        b.callback.closeWindow(b.fakeEngine)
        assertEquals(listOf(a), controller.tabsStates.toList())
        assertEquals(listOf<BrowserUiEvent>(BrowserUiEvent.TabClosedByPage), events)
    }

    @Test
    fun urlsAreRouted() {
        val tab = open("https://a.com/")
        events.clear()
        assertFalse(tab.callback.shouldOverrideUrlLoading("https://b.com/"))

        assertTrue(tab.callback.shouldOverrideUrlLoading("no-scheme"))
        assertTrue(events.isEmpty())

        assertTrue(tab.callback.shouldOverrideUrlLoading("intent://scan/#Intent;end"))
        assertEquals(BrowserUiEvent.OpenInExternalApp("intent://scan/#Intent;end"), events.last())

        assertTrue(tab.callback.shouldOverrideUrlLoading("unknown-scheme://x"))
        assertEquals(BrowserUiEvent.NoAppToOpenUrl, events.last())
    }

    @Test
    fun visitsAreLoggedWithTheTitleOfTheirTab() {
        val a = open("https://a.com/")
        a.title = "A"
        //engines stop calling the callback when their tab is detached, but the event may be late
        val aCallback = a.callback
        val b = open("https://b.com/")
        b.title = "B"
        aCallback.onVisited("https://a.com/")
        assertEquals(listOf("A https://a.com/"), storedHistory(1))
    }

    @Test
    fun visitsAreNotLoggedInIncognitoMode() {
        config.incognitoMode = true
        open("https://a.com/").callback.onVisited("https://a.com/")
        assertTrue(storedHistory(0).isEmpty())
    }

    @Test
    fun homePageLinksAreGivenToTheEngine() {
        runBlocking { controller.homePageLinks.load() }
        val tab = open("https://a.com/")
        assertTrue(controller.homePageLinks.links.value.isNotEmpty())
        assertEquals(controller.homePageLinks.links.value, tab.callback.getHomePageLinks())
    }

    @Test
    fun siteAdBlockSettingUpdatesTheState() {
        config.adBlockEnabled = true
        val tab = open("https://a.com/")
        assertTrue(state.adBlockEnabled)
        awaitWrite(controller.setSiteAdBlock(tab, false))
        assertFalse(state.adBlockEnabled)
    }

    @Test
    fun pageOfAnotherHostShowsItsSettingOnStart() {
        config.adBlockEnabled = true
        val tab = open("https://a.com/")
        awaitWrite(controller.setSiteAdBlock(tab, false))
        assertFalse(state.adBlockEnabled)

        tab.fakeEngine.loadedUrls += "https://b.com/"
        tab.callback.onPageStarted("https://b.com/")
        assertTrue(state.adBlockEnabled)
    }

    @Test
    fun uiHostCallsGoToTheViewHost() {
        val tab = open("https://a.com/")
        tab.callback.initiateVoiceSearch()
        assertEquals(listOf("initiateVoiceSearch"), host.uiHostCalls)
    }
}
