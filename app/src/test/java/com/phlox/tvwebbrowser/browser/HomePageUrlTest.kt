package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.Config.HomePageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomePageUrlTest {
    private val config get() = AppContext.provideConfig()

    private fun resolve(url: String = Config.HOME_URL_ALIAS) = HomePageUrl.resolve(url, config)

    @Test
    fun otherUrlsAreNotChanged() {
        assertEquals("https://example.com/", resolve("https://example.com/"))
        assertEquals("about:blank", resolve("about:blank"))
    }

    @Test
    fun builtInHomePage() {
        config.homePageMode = HomePageMode.HOME_PAGE
        assertEquals(Config.HOME_PAGE_URL, resolve())
    }

    @Test
    fun blankHomePage() {
        config.homePageMode = HomePageMode.BLANK
        assertEquals("about:blank", resolve())
    }

    @Test
    fun customAndSearchEngineHomePages() {
        config.homePage = "https://start.com/"
        config.homePageMode = HomePageMode.CUSTOM
        assertEquals("https://start.com/", resolve())
        config.homePageMode = HomePageMode.SEARCH_ENGINE
        assertEquals("https://start.com/", resolve())
    }

    @Test
    fun customModeWithoutUrlFallsBackToBuiltInHomePage() {
        config.homePageMode = HomePageMode.CUSTOM
        config.homePage = Config.HOME_URL_ALIAS
        assertEquals(Config.HOME_PAGE_URL, resolve())
    }

    @Test
    fun homePageIsTheAliasOrThePageItLoads() {
        config.homePage = Config.HOME_URL_ALIAS
        config.homePageMode = HomePageMode.HOME_PAGE
        assertTrue(HomePageUrl.isHomePage(Config.HOME_URL_ALIAS, config))
        assertTrue(HomePageUrl.isHomePage(Config.HOME_PAGE_URL, config))
        assertFalse(HomePageUrl.isHomePage("https://example.com/", config))
        assertFalse(HomePageUrl.isHomePage(null, config))
        config.homePage = "https://start.com/"
        config.homePageMode = HomePageMode.CUSTOM
        assertTrue(HomePageUrl.isHomePage("https://start.com/", config))
        assertFalse(HomePageUrl.isHomePage(Config.HOME_PAGE_URL, config))
    }
}
