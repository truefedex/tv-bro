package com.phlox.tvwebbrowser.browser

import android.os.Looper
import androidx.room.Room
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import kotlinx.coroutines.Job
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
import java.io.File

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

    private fun open(vararg urls: String): List<BrowserTab> = urls.map {
        controller.openInNewTab(it, controller.tabsStates.size, navigateImmediately = true)!!
    }

    private val current get() = controller.currentTab.value

    private fun awaitWrites() {
        val container = TVBro.container
        val job = container.appScope.launch { container.tabWrites.awaitPending() }
        while (!job.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    private fun awaitWrite(job: Job?) {
        while (job != null && !job.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    private fun storedAdBlock(host: String): Boolean? =
        db.query("SELECT adblock FROM hosts WHERE host_name = ?", arrayOf(host)).use { c ->
            if (c.moveToNext() && !c.isNull(0)) c.getInt(0) != 0 else null
        }

    private fun storedOrder(): List<String> = db.query("SELECT url FROM tabs ORDER BY position", null).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    /** url to selected */
    private fun storedSelection(): Map<String, Boolean> = db.query("SELECT url, selected FROM tabs", null).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) to (c.getInt(1) != 0) else null }.toMap()
    }

    private fun storedStateFile(url: String): String? =
        db.query("SELECT wv_state_file FROM tabs WHERE url = ?", arrayOf(url)).use { c ->
            if (c.moveToNext()) c.getString(0) else null
        }

    private fun stateFile(name: String) = File(File(context.filesDir, TabFilesStore.ENGINE_STATES_DIR), name)

    @Test
    fun engineStateIsWrittenOnSwitchAndRestoredAfterRestart() {
        val (a, _) = open("https://a.com/", "https://b.com/")
        awaitWrites()
        //a was left: its state is written; b is only stored as the selected tab
        val name = storedStateFile("https://a.com/")!!
        assertEquals("state of https://a.com/", stateFile(name).readText())
        assertNull(storedStateFile("https://b.com/"))

        //coming back to a restores its state from memory and does not write it again
        stateFile(name).setLastModified(1000L)
        controller.changeTab(a)
        awaitWrites()
        assertEquals(1000L, stateFile(name).lastModified())

        val restarted = BrowserController()
        restarted.viewHost = FakeTabViewHost(context)
        val job = restarted.loadState()
        while (!job.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        val restoredA = restarted.tabsStates.first { it.url == "https://a.com/" }
        restarted.changeTab(restoredA)
        assertTrue("restoreState" in restoredA.fakeEngine.log)
        restarted.clear()
    }

    @Test
    fun selectionIsStoredWhenTheTabChanges() {
        val (a, b) = open("https://a.com/", "https://b.com/")
        awaitWrites()
        //no activity pause: the new tab and its selection are already stored
        assertEquals(mapOf("https://a.com/" to false, "https://b.com/" to true), storedSelection())

        controller.changeTab(a)
        awaitWrites()
        assertEquals(mapOf("https://a.com/" to true, "https://b.com/" to false), storedSelection())
        assertSame(a, current)
        assertFalse(b.selected)
    }

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
        val a = BrowserTab(url = "https://a.com/")
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
    fun closingCurrentTabSelectsTheOneTakingItsPlace() {
        val (a, b, c) = open("https://a.com/", "https://b.com/", "https://c.com/")
        controller.changeTab(b)
        controller.closeTab(b)
        assertEquals(listOf(a, c), controller.tabsStates.toList())
        assertSame(c, current)
        assertEquals("detach(completely=true, destroyTab=true)", b.fakeEngine.log.last())
    }

    @Test
    fun closingLastCurrentTabSelectsThePreviousOne() {
        val (a, b) = open("https://a.com/", "https://b.com/")
        controller.changeTab(b)
        controller.closeTab(b)
        assertEquals(listOf(a), controller.tabsStates.toList())
        assertSame(a, current)
    }

    @Test
    fun closingBackgroundTabKeepsTheCurrentOne() {
        val (a, b, c) = open("https://a.com/", "https://b.com/", "https://c.com/")
        assertSame(c, current)
        c.fakeEngine.log.clear()
        controller.closeTab(b)
        assertEquals(listOf(a, c), controller.tabsStates.toList())
        assertSame(c, current)
        assertTrue(c.fakeEngine.log.isEmpty())
        assertEquals(listOf(0, 1), controller.tabsStates.map { it.position })
    }

    @Test
    fun closingATabThatIsNotOpenDoesNothing() {
        val (a) = open("https://a.com/")
        controller.closeTab(BrowserTab(url = "https://b.com/"))
        assertEquals(listOf(a), controller.tabsStates.toList())
        assertSame(a, current)
    }

    @Test
    fun closingOtherTabsKeepsTheTabAndSelectsIt() {
        val (a, b, c) = open("https://a.com/", "https://b.com/", "https://c.com/")
        controller.closeOtherTabs(b)
        assertEquals(listOf(b), controller.tabsStates.toList())
        assertSame(b, current)
        assertEquals("detach(completely=true, destroyTab=true)", a.fakeEngine.log.last())
        assertEquals("detach(completely=true, destroyTab=true)", c.fakeEngine.log.last())
        awaitWrites()
        assertEquals(mapOf("https://b.com/" to true), storedSelection())
    }

    @Test
    fun movingATabChangesThePositions() {
        val (a, b, c) = open("https://a.com/", "https://b.com/", "https://c.com/")
        assertTrue(controller.moveTab(a, 1))
        assertEquals(listOf(b, a, c), controller.tabsStates.toList())
        assertTrue(controller.moveTab(c, -2))
        assertEquals(listOf(c, b, a), controller.tabsStates.toList())
        assertEquals(listOf(0, 1, 2), controller.tabsStates.map { it.position })
        awaitWrites()
        assertEquals(listOf("https://c.com/", "https://b.com/", "https://a.com/"), storedOrder())
    }

    @Test
    fun tabDoesNotMoveOutOfTheList() {
        val (a, b) = open("https://a.com/", "https://b.com/")
        assertFalse(controller.moveTab(a, -1))
        assertFalse(controller.moveTab(b, 1))
        assertFalse(controller.moveTab(a, 0))
        assertEquals(listOf(a, b), controller.tabsStates.toList())
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
        val a = BrowserTab(url = "https://a.com/")
        val b = BrowserTab(url = "https://b.com/", selected = true)
        controller.tabsStates.addAll(listOf(a, b))
        controller.selectStartTab()
        assertSame(b, current)
        assertEquals(listOf("https://b.com/"), b.fakeEngine.loadedUrls)
    }

    @Test
    fun startTabIsTheFirstOneWhenNoneIsSelected() {
        val a = BrowserTab(url = "https://a.com/")
        val b = BrowserTab(url = "https://b.com/")
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
    fun homeAliasIsResolvedForTheEngine() {
        AppContext.provideConfig().homePageMode = Config.HomePageMode.HOME_PAGE
        val (a) = open("https://a.com/")
        controller.navigate(Config.HOME_URL_ALIAS)
        //the tab keeps the alias until the engine reports the real url
        assertEquals(Config.HOME_URL_ALIAS, a.url)
        assertEquals(Config.HOME_PAGE_URL, a.fakeEngine.loadedUrls.last())
    }

    @Test
    fun restoredHomeTabLoadsTheResolvedUrl() {
        AppContext.provideConfig().homePageMode = Config.HomePageMode.BLANK
        val tab = BrowserTab(url = Config.HOME_URL_ALIAS)
        controller.tabsStates.add(tab)
        controller.changeTab(tab)
        assertEquals(listOf("about:blank"), tab.fakeEngine.loadedUrls)
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
    fun siteAdBlockSettingAppliesToAllTabsOfTheHostAndReloads() {
        AppContext.provideConfig().adBlockEnabled = true
        val (a, otherHost, sameHost) = open("https://a.com/", "https://b.com/", "https://a.com/page")
        controller.changeTab(a)
        awaitWrite(controller.setSiteAdBlock(a, false))
        assertFalse(controller.isAdBlockEnabled(a))
        assertFalse(controller.isAdBlockEnabled(sameHost))
        assertTrue(controller.isAdBlockEnabled(otherHost))
        assertEquals(false, a.fakeEngine.adblockSetting)
        assertEquals("reload", a.fakeEngine.log.last())
        assertEquals(false, storedAdBlock("a.com"))
    }

    @Test
    fun siteAdBlockSettingEqualToTheGlobalOneIsNotKept() {
        AppContext.provideConfig().adBlockEnabled = true
        val (a) = open("https://a.com/")
        awaitWrite(controller.setSiteAdBlock(a, false))
        awaitWrite(controller.setSiteAdBlock(a, true))
        assertNull(storedAdBlock("a.com"))
        //so the site follows a later change of the global setting
        AppContext.provideConfig().adBlockEnabled = false
        assertFalse(controller.isAdBlockEnabled(a))
    }

    @Test
    fun siteAdBlockSettingNeedsAHost() {
        val (home) = open(Config.HOME_URL_ALIAS)
        assertNull(controller.setSiteAdBlock(home, false))
    }

    @Test
    fun trimMemorySkipsTheSelectedTab() {
        val (a, b) = open("https://a.com/", "https://b.com/")
        controller.trimMemory()
        assertTrue(a.fakeEngine.log.contains("trimMemory"))
        assertFalse(b.fakeEngine.log.contains("trimMemory"))
    }

    @Test
    fun closingAllTabsDestroysTheirEngines() {
        val (a, b) = open("https://a.com/", "https://b.com/")
        val neverShown = BrowserTab(url = "https://c.com/")
        controller.tabsStates.add(neverShown)
        controller.onCloseAllTabs()
        assertTrue(controller.tabsStates.isEmpty())
        assertEquals("detach(completely=true, destroyTab=true)", a.fakeEngine.log.last())
        assertEquals("detach(completely=true, destroyTab=true)", b.fakeEngine.log.last())
        //engines are not created just to be destroyed
        assertFalse(neverShown.isWebEngineCreated)
    }

    @Test
    fun detachingTheActivityDoesNotCreateEngines() {
        val (a) = open("https://a.com/")
        val neverShown = BrowserTab(url = "https://b.com/")
        controller.tabsStates.add(neverShown)
        controller.onDetachActivity(host)
        assertEquals("detach(completely=true, destroyTab=false)", a.fakeEngine.log.last())
        assertFalse(neverShown.isWebEngineCreated)
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
