package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.Config.HomePageLinksMode
import com.phlox.tvwebbrowser.Config.HomePageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsModelTest {
    private lateinit var config: Config
    private lateinit var model: SettingsModel

    @Before
    fun setUp() {
        config = AppContext.provideConfig()
        model = SettingsModel()
    }

    @Test
    fun defaults() {
        assertEquals(HomePageMode.HOME_PAGE, model.homePageMode)
        assertEquals(Config.HOME_URL_ALIAS, model.homePage)
    }

    @Test
    fun searchEngineHomePageIsSearchEngineOrigin() {
        config.searchEngineURL.value = "https://www.bing.com/search?q=[query]"
        model.setHomePageProperties(HomePageMode.SEARCH_ENGINE, null, HomePageLinksMode.MOST_VISITED)
        assertEquals("https://www.bing.com", model.homePage)
        assertEquals(HomePageMode.SEARCH_ENGINE, model.homePageMode)
        assertEquals(HomePageLinksMode.MOST_VISITED, model.homePageLinksMode)
    }

    @Test
    fun searchEngineWithoutSchemeFallsBackToHomeAlias() {
        config.searchEngineURL.value = "duckduckgo.com/?q=[query]"
        model.setHomePageProperties(HomePageMode.SEARCH_ENGINE, null, HomePageLinksMode.BOOKMARKS)
        assertEquals(Config.HOME_URL_ALIAS, model.homePage)
    }

    @Test
    fun homeAndBlankModesUseHomeAlias() {
        model.setHomePageProperties(HomePageMode.CUSTOM, "https://example.com", HomePageLinksMode.BOOKMARKS)
        model.setHomePageProperties(HomePageMode.HOME_PAGE, "https://ignored.com", HomePageLinksMode.LATEST_HISTORY)
        assertEquals(Config.HOME_URL_ALIAS, model.homePage)
        assertEquals(HomePageLinksMode.LATEST_HISTORY, model.homePageLinksMode)

        model.setHomePageProperties(HomePageMode.CUSTOM, "https://example.com", HomePageLinksMode.BOOKMARKS)
        model.setHomePageProperties(HomePageMode.BLANK, null, HomePageLinksMode.BOOKMARKS)
        assertEquals(Config.HOME_URL_ALIAS, model.homePage)
        assertEquals(HomePageMode.BLANK, model.homePageMode)
    }

    @Test
    fun customHomePage() {
        model.setHomePageProperties(HomePageMode.CUSTOM, "https://example.com/start", HomePageLinksMode.BOOKMARKS)
        assertEquals("https://example.com/start", model.homePage)

        model.setHomePageProperties(HomePageMode.CUSTOM, null, HomePageLinksMode.BOOKMARKS)
        assertEquals(Config.HOME_URL_ALIAS, model.homePage)
    }

    @Test
    fun changingSearchEngineUpdatesSearchEngineHomePage() {
        model.setHomePageProperties(HomePageMode.SEARCH_ENGINE, null, HomePageLinksMode.BOOKMARKS)
        model.setSearchEngineURL("https://duckduckgo.com/?q=[query]")
        assertEquals("https://duckduckgo.com/?q=[query]", config.searchEngineURL.value)
        assertEquals("https://duckduckgo.com", model.homePage)
    }

    @Test
    fun changingSearchEngineKeepsOtherHomePages() {
        model.setHomePageProperties(HomePageMode.CUSTOM, "https://example.com", HomePageLinksMode.BOOKMARKS)
        model.setSearchEngineURL("https://duckduckgo.com/?q=[query]")
        assertEquals("https://example.com", model.homePage)
    }

    @Test
    fun legacySearchEngineAsHomePageFlagIsMigrated() {
        config.prefs.edit()
            .putInt(Config.HOME_PAGE_MODE, HomePageMode.HOME_PAGE.ordinal)
            .putBoolean(Config.SEARCH_ENGINE_AS_HOME_PAGE_KEY, true)
            .commit()
        assertEquals(HomePageMode.SEARCH_ENGINE, model.homePageMode)
        assertFalse(config.prefs.contains(Config.SEARCH_ENGINE_AS_HOME_PAGE_KEY))
        assertEquals(HomePageMode.SEARCH_ENGINE, model.homePageMode)
    }

    @Test
    fun invalidStoredModesFallBackToFirst() {
        config.prefs.edit()
            .putInt(Config.HOME_PAGE_MODE, 100)
            .putInt(Config.HOME_PAGE_SUGGESTIONS_MODE, -1)
            .commit()
        assertEquals(HomePageMode.HOME_PAGE, model.homePageMode)
        assertEquals(HomePageLinksMode.BOOKMARKS, model.homePageLinksMode)
    }
}
