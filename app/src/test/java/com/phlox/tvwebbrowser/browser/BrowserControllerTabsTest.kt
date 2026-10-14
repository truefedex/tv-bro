package com.phlox.tvwebbrowser.browser

import android.os.Looper
import androidx.room.Room
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
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
class BrowserControllerTabsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var controller: BrowserController
    private lateinit var host: FakeTabViewHost

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase.setInstanceForTests(db)
        WebEngineFactory.initializeForTests(FakeWebEngine.provider)
        controller = BrowserController()
        host = FakeTabViewHost(context)
        controller.viewHost = host
    }

    @After
    fun tearDown() {
        //tabs are saved in the background, let them finish before the database is closed
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

    private fun open(vararg urls: String): List<WebTabState> = urls.map {
        controller.openInNewTab(it, controller.tabsStates.size, navigateImmediately = true)!!
    }

    private val current get() = controller.currentTab.value

    @Test
    fun openInNewTabSelectsAttachesAndLoadsTheTab() {
        val (a) = open("https://a.com/")
        val b = controller.openInNewTab("https://b.com/", 0, navigateImmediately = true)!!

        assertEquals(listOf(b, a), controller.tabsStates.toList())
        assertSame(b, current)
        assertTrue(b.selected)
        assertFalse(a.selected)
        assertSame(host.tabsContainer, b.fakeEngine.parent)
        assertTrue(b.fakeEngine.callback != null)
        assertEquals("https://b.com/", b.fakeEngine.url)
        assertEquals(listOf(0, 1), controller.tabsStates.map { it.position })
    }

    @Test
    fun openInNewTabWithoutNavigationLoadsNothing() {
        //the view is created before the tab is selected, so there is nothing to restore or reload
        val tab = controller.openInNewTab("https://a.com/", 0, navigateImmediately = false)!!
        assertTrue(tab.fakeEngine.loadedUrls.isEmpty())
    }

    @Test
    fun restoredTabWithoutStateLoadsItsUrl() {
        val a = WebTabState(url = "https://a.com/")
        controller.tabsStates.add(a)
        controller.changeTab(a)
        assertEquals(listOf("https://a.com/"), a.fakeEngine.loadedUrls)
    }

    @Test
    fun openInNewTabFailsWithoutEngineView() {
        host.canCreateViews = false
        assertNull(controller.openInNewTab("https://a.com/", 0, navigateImmediately = true))
        assertTrue(controller.tabsStates.isEmpty())
        assertNull(current)
    }

    @Test
    fun openInNewTabFailsWithoutViewHost() {
        controller.viewHost = null
        assertNull(controller.openInNewTab("https://a.com/", 0, navigateImmediately = true))
        assertTrue(controller.tabsStates.isEmpty())
    }

    @Test
    fun changeTabDetachesAndPausesThePreviousTab() {
        val (a, b) = open("https://a.com/", "https://b.com/")
        a.fakeEngine.log.clear()
        b.fakeEngine.log.clear()

        controller.changeTab(a)

        assertSame(a, current)
        assertTrue(a.selected)
        assertFalse(b.selected)
        assertEquals(listOf("detach(completely=false, destroyTab=false)"), b.fakeEngine.log)
        assertEquals("state of https://b.com/", b.savedState)
        assertEquals(listOf("attach"), a.fakeEngine.log)
    }

    @Test
    fun changeToTheCurrentTabWithViewDoesNothing() {
        val (a) = open("https://a.com/")
        a.fakeEngine.log.clear()
        controller.changeTab(a)
        assertTrue(a.fakeEngine.log.isEmpty())
    }

    @Test
    fun closingCurrentTabSelectsThePreviousOne() {
        val (a, b, c) = open("https://a.com/", "https://b.com/", "https://c.com/")
        controller.changeTab(b)
        controller.closeTab(b)
        assertEquals(listOf(a, c), controller.tabsStates.toList())
        assertSame(a, current)
        assertEquals("detach(completely=true, destroyTab=true)", b.fakeEngine.log.last())
    }

    @Test
    fun closingFirstCurrentTabSelectsTheNextOne() {
        val (a, b) = open("https://a.com/", "https://b.com/")
        controller.changeTab(a)
        controller.closeTab(a)
        assertEquals(listOf(b), controller.tabsStates.toList())
        assertSame(b, current)
    }

    // current behavior: the neighbour tab is selected even if a background tab is closed
    @Test
    fun closingBackgroundTabSelectsItsNeighbour() {
        val (a, b, c) = open("https://a.com/", "https://b.com/", "https://c.com/")
        assertSame(c, current)
        controller.closeTab(b)
        assertEquals(listOf(a, c), controller.tabsStates.toList())
        assertSame(a, current)
    }

    @Test
    fun closingTheLastTabOpensTheHomePage() {
        val (a) = open("https://a.com/")
        controller.closeTab(a)
        assertEquals(1, controller.tabsStates.size)
        val home = controller.tabsStates[0]
        assertSame(home, current)
        assertEquals(Config.HOME_URL_ALIAS, home.url)
    }

    @Test
    fun startTabIsTheSelectedOne() {
        val a = WebTabState(url = "https://a.com/")
        val b = WebTabState(url = "https://b.com/", selected = true)
        controller.tabsStates.addAll(listOf(a, b))
        controller.selectStartTab()
        assertSame(b, current)
        assertEquals(listOf("https://b.com/"), b.fakeEngine.loadedUrls)
    }

    @Test
    fun startTabIsTheFirstOneWhenNoneIsSelected() {
        val a = WebTabState(url = "https://a.com/")
        val b = WebTabState(url = "https://b.com/")
        controller.tabsStates.addAll(listOf(a, b))
        controller.selectStartTab()
        assertSame(a, current)
    }

    @Test
    fun startTabIsTheHomePageWithoutTabs() {
        AppContext.provideConfig().homePage = "https://home.com/"
        controller.selectStartTab()
        assertEquals("https://home.com/", current!!.url)
    }

    @Test
    fun windowTabIsInsertedAfterTheCurrentOne() {
        val (a, b) = open("https://a.com/", "https://b.com/")
        controller.changeTab(a)
        val view = controller.openWindowTab()!!
        val windowTab = controller.tabsStates[1]
        assertEquals(listOf(a, windowTab, b), controller.tabsStates.toList())
        assertSame(windowTab, current)
        assertSame(view, windowTab.fakeEngine.engineView)
    }

    @Test
    fun navigateLoadsTheUrlInTheCurrentTab() {
        val (a) = open("https://a.com/")
        controller.navigate("https://b.com/")
        assertEquals("https://b.com/", a.url)
        assertEquals("https://b.com/", a.fakeEngine.url)
        assertEquals(1, controller.tabsStates.size)
    }

    @Test
    fun navigateWithoutTabsOpensOne() {
        controller.navigate("https://a.com/")
        assertEquals("https://a.com/", current!!.url)
    }

    @Test
    fun goBackReportsWhetherThereWasHistory() {
        val (a) = open("https://a.com/")
        assertFalse(controller.goBack())
        a.fakeEngine.canGoBackValue = true
        assertTrue(controller.goBack())
        assertEquals("goBack", a.fakeEngine.log.last())
    }

    @Test
    fun tabIsFoundBySession() {
        val (a, b) = open("https://a.com/", "https://b.com/")
        assertSame(b, controller.findTabBySession(b.fakeEngine))
        assertNull(controller.findTabBySession(Any()))
        assertSame(a, controller.findTabBySession(a.fakeEngine))
    }

    @Test
    fun adBlockToggleOverridesTheGlobalSetting() {
        AppContext.provideConfig().adBlockEnabled = true
        val (a) = open("https://a.com/")
        controller.toggleAdBlockForCurrentTab()
        assertEquals(false, a.adblock)
        assertEquals(false, a.fakeEngine.adblockSetting)
        controller.toggleAdBlockForCurrentTab()
        assertEquals(true, a.adblock)
    }

    @Test
    fun trimMemorySkipsTheSelectedTab() {
        val (a, b) = open("https://a.com/", "https://b.com/")
        controller.trimMemory()
        assertTrue(a.fakeEngine.log.contains("trimMemory"))
        assertFalse(b.fakeEngine.log.contains("trimMemory"))
    }

    @Test
    fun detachingAnotherActivityKeepsTheViewHost() {
        open("https://a.com/")
        controller.onDetachActivity(FakeTabViewHost(context))
        assertSame(host, controller.viewHost)
        controller.onDetachActivity(host)
        assertNull(controller.viewHost)
    }
}
