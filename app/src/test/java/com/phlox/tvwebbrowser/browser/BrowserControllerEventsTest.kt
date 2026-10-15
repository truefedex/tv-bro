package com.phlox.tvwebbrowser.browser

import android.os.Looper
import androidx.room.Room
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

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
    fun certificateErrorLastsUntilNavigation() {
        val tab = open("https://a.com/")
        tab.callback.onPageCertificateError("https://a.com/")
        assertTrue(state.certificateError)
        tab.callback.onPageStarted(null)
        assertTrue(state.certificateError)
        controller.navigate("https://b.com/")
        assertFalse(state.certificateError)
    }

    @Test
    fun certificateErrorBelongsToItsTab() {
        val a = open("https://a.com/")
        a.callback.onPageCertificateError("https://a.com/")
        open("https://b.com/")
        assertFalse(state.certificateError)
        controller.changeTab(a)
        assertTrue(state.certificateError)
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
    fun pageOfAnotherHostGetsItsSettingOnStart() {
        config.adBlockEnabled = true
        val tab = open("https://a.com/")
        awaitWrite(controller.setSiteAdBlock(tab, false))
        assertEquals(false, tab.fakeEngine.adblockSetting)

        tab.fakeEngine.loadedUrls += "https://b.com/"
        tab.callback.onPageStarted("https://b.com/")
        assertEquals(true, tab.fakeEngine.adblockSetting)
        assertTrue(state.adBlockEnabled)
    }

    @Test
    fun uiHostCallsGoToTheViewHost() {
        val tab = open("https://a.com/")
        tab.callback.initiateVoiceSearch()
        assertEquals(listOf("initiateVoiceSearch"), host.uiHostCalls)
    }
}
