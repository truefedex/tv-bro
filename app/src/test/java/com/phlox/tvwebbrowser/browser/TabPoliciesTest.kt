package com.phlox.tvwebbrowser.browser

import android.net.Uri
import androidx.room.Room
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val adRequests = ArrayList<String>()
    private val adBlocker = object : AdBlocker {
        override fun isAd(url: Uri, type: String?, baseUri: Uri): Boolean {
            adRequests += "$url $type $baseUri"
            return url.host == "ads.com"
        }
    }

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

    private fun policies(tab: BrowserTab) = TabPolicies(tab, controller, adBlocker, config)

    private fun setPopupBlockLevel(host: String, level: Int) = runBlocking {
        val hostConfig = HostConfig(host)
        hostConfig.popupBlockLevel = level
        db.hostsDao().insert(hostConfig)
    }

    @Test
    fun adsAreCheckedByTheAdBlocker() {
        val policies = policies(BrowserTab(url = "https://site.com/"))
        assertEquals(true, policies.isAd(Uri.parse("https://ads.com/a.js"), "script", Uri.parse("https://site.com/")))
        assertEquals(false, policies.isAd(Uri.parse("https://cdn.com/a.js"), null, Uri.parse("https://site.com/")))
        assertEquals("https://ads.com/a.js script https://site.com/", adRequests[0])
    }

    @Test
    fun adBlockingFollowsTheGlobalSettingUnlessTheCurrentTabOverridesIt() {
        val tab = BrowserTab(url = "https://site.com/")
        val policies = policies(tab)
        config.adBlockEnabled = true
        assertTrue(policies.isAdBlockingEnabled())
        config.adBlockEnabled = false
        assertFalse(policies.isAdBlockingEnabled())

        controller.currentTab.value = tab
        tab.adblock = true
        assertTrue(policies.isAdBlockingEnabled())
    }

    // current behavior: the override of the current tab is used even for another tab's engine
    @Test
    fun adBlockingUsesTheCurrentTabOverride() {
        config.adBlockEnabled = true
        val current = BrowserTab(url = "https://a.com/", adblock = false)
        controller.currentTab.value = current
        assertFalse(policies(BrowserTab(url = "https://b.com/")).isAdBlockingEnabled())
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
