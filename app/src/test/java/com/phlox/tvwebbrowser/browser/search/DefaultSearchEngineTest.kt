package com.phlox.tvwebbrowser.browser.search

import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class DefaultSearchEngineTest {
    private val config get() = AppContext.provideConfig()
    private val qwant get() = Config.SearchEnginesURLs[Config.QWANT_INDEX]

    @Before
    fun setUp() {
        //in partner builds TVBro.onCreate has chosen the engine for the country of the machine already
        config.prefs.edit().remove(Config.DEFAULT_SEARCH_ENGINE_VERSION_KEY)
            .remove(Config.SEARCH_ENGINE_URL_PREF_KEY).remove(Config.HOME_PAGE_KEY).commit()
        config.searchEngineURL.value = Config.SearchEnginesURLs[0]
        config.prefs.edit().remove(Config.SEARCH_ENGINE_URL_PREF_KEY).commit()
    }

    @Test
    fun qwantIsTheDefaultInThePartnerCountriesOfPartnerBuilds() {
        assertEquals(Config.QWANT_INDEX, DefaultSearchEngine.defaultEngineIndex("FR", partner = true))
        assertEquals(Config.QWANT_INDEX, DefaultSearchEngine.defaultEngineIndex("us", partner = true))
        assertEquals(0, DefaultSearchEngine.defaultEngineIndex("UA", partner = true))
        assertEquals(0, DefaultSearchEngine.defaultEngineIndex(null, partner = true))
        assertEquals(0, DefaultSearchEngine.defaultEngineIndex("FR", partner = false))
        assertEquals(19, DefaultSearchEngine.PARTNER_COUNTRIES.size)
    }

    @Test
    fun aUserWithoutAChoiceOrWithTheOldDefaultGetsQwantOnce() {
        assertFalse(config.searchEngineSaved)
        DefaultSearchEngine.applyOnce(config, { "DE" }, partner = true)
        assertEquals(qwant, config.searchEngineURL.value)
        //once: the user's later choice of Google stays, also in a partner country
        config.setSearchEngine(Config.SearchEnginesURLs[0])
        DefaultSearchEngine.applyOnce(config, { "DE" }, partner = true)
        assertEquals(Config.SearchEnginesURLs[0], config.searchEngineURL.value)
    }

    @Test
    fun theOldDefaultSavedIsSwitched() {
        config.searchEngineURL.value = Config.SearchEnginesURLs[0]
        DefaultSearchEngine.applyOnce(config, { "GB" }, partner = true)
        assertEquals(qwant, config.searchEngineURL.value)
    }

    @Test
    fun anotherEngineTheUserChoseStays() {
        config.searchEngineURL.value = Config.SearchEnginesURLs[1]
        DefaultSearchEngine.applyOnce(config, { "FR" }, partner = true)
        assertEquals(Config.SearchEnginesURLs[1], config.searchEngineURL.value)
        assertEquals(Config.DEFAULT_SEARCH_ENGINE_VERSION, config.defaultSearchEngineVersion)
    }

    @Test
    fun otherCountriesKeepGoogle() {
        DefaultSearchEngine.applyOnce(config, { "UA" }, partner = true)
        assertEquals(Config.SearchEnginesURLs[0], config.searchEngineURL.value)
        assertFalse(config.searchEngineSaved)
        //a later move to a partner country changes nothing
        DefaultSearchEngine.applyOnce(config, { "FR" }, partner = true)
        assertEquals(Config.SearchEnginesURLs[0], config.searchEngineURL.value)
    }

    @Test
    fun aBuildWithoutThePartnerIdUsesNothingUp() {
        DefaultSearchEngine.applyOnce(config, { "FR" }, partner = false)
        assertEquals(Config.SearchEnginesURLs[0], config.searchEngineURL.value)
        assertEquals(0, config.defaultSearchEngineVersion)
        DefaultSearchEngine.applyOnce(config, { "FR" }, partner = true)
        assertEquals(qwant, config.searchEngineURL.value)
    }

    @Test
    fun theHomePageOfTheSearchEngineFollows() {
        config.homePageMode = Config.HomePageMode.SEARCH_ENGINE
        DefaultSearchEngine.applyOnce(config, { "FR" }, partner = true)
        assertEquals(Config.SearchEnginesHomeURLs[Config.QWANT_INDEX], config.homePage)
        assertTrue(config.homePage.startsWith("https://www.qwant.com/"))
    }

    @Test
    fun theCountryComesFromTheTimeZone() {
        assertEquals("FR", RegionDetector.timeZoneCountry("Europe/Paris"))
        assertEquals("CA", RegionDetector.timeZoneCountry("America/Toronto"))
        assertEquals("AU", RegionDetector.timeZoneCountry("Australia/Sydney"))
        assertNull(RegionDetector.timeZoneCountry("UTC"))
        assertNull(RegionDetector.timeZoneCountry("Not/AZone"))
        assertEquals("GB", RegionDetector.valid(" gb "))
        assertNull(RegionDetector.valid("001"))
        assertNull(RegionDetector.valid("ZZ"))
        assertNull(RegionDetector.valid(""))
    }

    @Test
    fun theLanguageOfQwantsSuggestions() {
        assertEquals("fr_fr", SearchEngines.suggestLang(Locale.FRANCE, "BE"))
        assertEquals("de_de", SearchEngines.suggestLang(Locale.GERMAN, "DE"))
        assertEquals("en_de", SearchEngines.suggestLang(Locale.ENGLISH, "DE"))
        assertEquals("en_gb", SearchEngines.suggestLang(Locale.ENGLISH, null))
        assertEquals("en_gb", SearchEngines.suggestLang(Locale.ROOT, "FR"))
    }

    @Test
    fun theAddressesOfQwantTheAppMadeAreThoseOfTheList() {
        assertEquals(qwant, Config.canonicalSearchEngineUrl("https://www.qwant.com/?q=[query]&t=web"))
        assertEquals(qwant, Config.canonicalSearchEngineUrl("https://www.qwant.com/?q=[query]&t=web&client=old-id"))
        //the user's own address of Qwant stays theirs
        val own = "https://www.qwant.com/?q=[query]&t=web&l=fr"
        assertEquals(own, Config.canonicalSearchEngineUrl(own))
        assertEquals(own, Config.canonicalSearchEngineUrl(own))
        assertEquals("https://www.qwant.com/?q=[query]", Config.canonicalSearchEngineUrl("https://www.qwant.com/?q=[query]"))
        //the home page sends the address without the partner ID
        config.setSearchEngine(Config.searchEngineUrlOfHomePage("qwant", "https://www.qwant.com/?q=[query]&t=web"))
        assertEquals(qwant, config.searchEngineURL.value)
        assertEquals("qwant", config.guessSearchEngineName())
        assertEquals("https://example.com/?q=[query]",
            Config.searchEngineUrlOfHomePage("custom", "https://example.com/?q=[query]"))
        if (Config.QWANT_PARTNER) assertTrue(qwant.endsWith("&client=${Config.QWANT_CLIENT_ID}"))
        else assertFalse(qwant.contains("client="))
    }
}
