package com.phlox.tvwebbrowser.browser

import android.os.Looper
import androidx.room.Room
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.HomePageLink
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

@RunWith(RobolectricTestRunner::class)
class BrowserControllerEventsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val config get() = AppContext.provideConfig()
    private lateinit var db: AppDatabase
    private lateinit var controller: BrowserController
    private lateinit var host: FakeTabViewHost
    private val events = ArrayList<BrowserUiEvent>()
    private val eventsScope = CoroutineScope(Dispatchers.Unconfined)

    private class FakeHistory : BrowsingHistory {
        override val homePageLinks = listOf(HomePageLink("Example", "https://example.com/", null))
        val visited = ArrayList<String>()
        val titleUpdates = ArrayList<WebTabState>()
        val usefulBookmarks = ArrayList<Int>()
        override fun logVisitedHistory(title: String?, url: String, faviconHash: String?) {
            visited += "$title $url"
        }
        override fun onTabTitleUpdated(tab: WebTabState) {
            titleUpdates += tab
        }
        override fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int) {
            usefulBookmarks += bookmarkOrder
        }
    }

    private val history = FakeHistory()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase.setInstanceForTests(db)
        WebEngineFactory.initializeForTests(FakeWebEngine.provider)
        controller = BrowserController()
        host = FakeTabViewHost(context)
        controller.viewHost = host
        controller.history = history
        eventsScope.launch { controller.uiEvents.collect { events += it } }
    }

    @After
    fun tearDown() {
        eventsScope.cancel()
        val container = TVBro.instance.container
        val job = container.appScope.launch { container.tabWrites.awaitPending() }
        while (!job.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        controller.clear()
        AppDatabase.setInstanceForTests(null)
        db.close()
    }

    private fun open(url: String): WebTabState =
        controller.openInNewTab(url, controller.tabsStates.size, navigateImmediately = true)!!

    private val WebTabState.callback: WebEngineWindowProviderCallback get() = fakeEngine.callback!!
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
        assertEquals(listOf(tab), history.titleUpdates)
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
        assertEquals("https://b.com/", tab.lastLoadingUrl)

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
        assertEquals(listOf("A https://a.com/"), history.visited)
    }

    @Test
    fun visitsAreNotLoggedInIncognitoMode() {
        config.incognitoMode = true
        open("https://a.com/").callback.onVisited("https://a.com/")
        assertTrue(history.visited.isEmpty())
    }

    @Test
    fun homePageDataComesFromTheHistory() {
        val tab = open("https://a.com/")
        assertEquals(history.homePageLinks, tab.callback.getHomePageLinks())
        tab.callback.markBookmarkRecommendationAsUseful(3)
        assertEquals(listOf(3), history.usefulBookmarks)
    }

    @Test
    fun adBlockToggleUpdatesTheState() {
        config.adBlockEnabled = true
        open("https://a.com/")
        assertTrue(state.adBlockEnabled)
        controller.toggleAdBlockForCurrentTab()
        assertFalse(state.adBlockEnabled)
    }

    @Test
    fun uiHostCallsGoToTheViewHost() {
        val tab = open("https://a.com/")
        tab.callback.initiateVoiceSearch()
        assertEquals(listOf("initiateVoiceSearch"), host.uiHostCalls)
    }
}
