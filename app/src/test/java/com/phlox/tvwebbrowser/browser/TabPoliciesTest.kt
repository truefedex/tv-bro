package com.phlox.tvwebbrowser.browser

import androidx.room.Room
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TabPoliciesTest {
    private lateinit var db: AppDatabase
    private lateinit var controller: BrowserController
    private val config get() = AppContext.provideConfig()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        AppDatabase.setInstanceForTests(db)
        //the controller reads the current tab engine state
        WebEngineFactory.initializeForTests(FakeWebEngine.provider)
        controller = BrowserController()
    }

    @After
    fun tearDown() {
        controller.clear()
        AppDatabase.setInstanceForTests(null)
        db.close()
    }

    private fun policies(tab: BrowserTab) = TabPolicies(tab, controller)

    /** Also loads the config, as the controller does when a page of the host starts loading */
    private fun setPopupBlockLevel(host: String, level: Int) = runBlocking {
        TVBro.container.data.hostConfigs.setPopupBlockLevel(host, level)
    }

    private fun setAdBlock(host: String, adblock: Boolean?) = runBlocking {
        TVBro.container.data.hostConfigs.setAdBlock(host, adblock)
    }

    @Test
    fun adBlockingFollowsTheGlobalSettingUnlessTheSiteHasItsOwn() {
        val policies = policies(BrowserTab(url = "https://site.com/"))
        config.adBlockEnabled = true
        assertTrue(policies.isAdBlockingEnabled())
        config.adBlockEnabled = false
        assertFalse(policies.isAdBlockingEnabled())

        setAdBlock("site.com", true)
        assertTrue(policies.isAdBlockingEnabled())
    }

    @Test
    fun adBlockingUsesTheSettingOfTheTabsOwnHost() {
        config.adBlockEnabled = true
        setAdBlock("a.com", false)
        controller.currentTab.value = BrowserTab(url = "https://a.com/")
        assertTrue(policies(BrowserTab(url = "https://b.com/")).isAdBlockingEnabled())
    }

    @Test
    fun adBlockingFollowsTheTabUrl() {
        config.adBlockEnabled = true
        setAdBlock("a.com", false)
        val tab = BrowserTab(url = "https://b.com/")
        val policies = policies(tab)
        assertTrue(policies.isAdBlockingEnabled())
        tab.url = "https://a.com/x"
        assertFalse(policies.isAdBlockingEnabled())
    }

    @Test
    fun dialogsAreBlockedByDefault() {
        assertTrue(policies(BrowserTab(url = "https://site.com/")).isDialogsBlockingEnabled())
    }

    @Test
    fun dialogsAreNotBlockedOnTheHomePage() {
        assertFalse(policies(BrowserTab(url = Config.HOME_PAGE_URL)).isDialogsBlockingEnabled())
    }

    @Test
    fun hostSettingIsUsedForNewWindows() {
        setPopupBlockLevel("site.com", HostConfig.POPUP_BLOCK_NONE)
        val policies = policies(BrowserTab(url = "https://site.com/page"))
        assertFalse(policies.shouldBlockNewWindow(dialog = false, userGesture = false))
        assertFalse(policies.isDialogsBlockingEnabled())

        val otherHost = policies(BrowserTab(url = "https://other.com/"))
        assertTrue(otherHost.shouldBlockNewWindow(dialog = false, userGesture = false))
        assertFalse(otherHost.shouldBlockNewWindow(dialog = false, userGesture = true))
    }

    @Test
    fun hostSettingFollowsTheTabUrl() {
        setPopupBlockLevel("strict.com", HostConfig.POPUP_BLOCK_NEW_TABS_EVEN_BY_USER_GESTURE)
        val tab = BrowserTab(url = "https://site.com/")
        val policies = policies(tab)
        assertFalse(policies.shouldBlockNewWindow(dialog = false, userGesture = true))
        tab.url = "https://strict.com/"
        assertTrue(policies.shouldBlockNewWindow(dialog = false, userGesture = true))
    }
}
