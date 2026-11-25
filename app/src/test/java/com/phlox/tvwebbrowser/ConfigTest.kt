package com.phlox.tvwebbrowser

import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.webengine.SiteView
import com.phlox.tvwebbrowser.widgets.cursor.PointerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigTest {
    private val config get() = AppContext.provideConfig()

    @Test
    fun adBlockEnabledIsOnByDefault() {
        assertTrue(config.adBlockEnabled)
    }

    @Test
    fun historyIsKeptForAYearByDefault() {
        assertEquals(Config.HistoryRetention.YEAR, config.historyRetention)
        config.historyRetention = Config.HistoryRetention.WEEK
        assertEquals(Config.HistoryRetention.WEEK, config.historyRetention)
        //a value this version does not know
        config.prefs.edit().putString(Config.HISTORY_RETENTION_KEY, "FOREVER").commit()
        assertEquals(Config.HistoryRetention.YEAR, config.historyRetention)
    }

    @Test
    fun cursorPointerFollowsTheSiteViewUnlessChosen() {
        assertEquals(Config.CursorPointer.AUTO, config.cursorPointer)
        val auto = Config.CursorPointer.AUTO
        assertEquals(PointerMode.TOUCH, auto.pointerModeOf(SiteView.MOBILE))
        assertEquals(PointerMode.MOUSE, auto.pointerModeOf(SiteView.TABLET))
        assertEquals(PointerMode.MOUSE, auto.pointerModeOf(SiteView.DESKTOP))
        for (view in SiteView.entries) {
            assertEquals(PointerMode.MOUSE, Config.CursorPointer.MOUSE.pointerModeOf(view))
            assertEquals(PointerMode.TOUCH, Config.CursorPointer.TOUCH.pointerModeOf(view))
        }
        config.cursorPointer = Config.CursorPointer.TOUCH
        assertEquals(Config.CursorPointer.TOUCH, config.cursorPointer)
        //a value this version does not know
        config.prefs.edit().putString(Config.CURSOR_POINTER_KEY, "PEN").commit()
        assertEquals(Config.CursorPointer.AUTO, config.cursorPointer)
    }

    @Test
    fun adBlockEnabledFollowsThePrefs() {
        //another process (incognito) writes the same prefs
        config.prefs.edit().putBoolean(Config.ADBLOCK_ENABLED_PREF_KEY, false).commit()
        assertFalse(config.adBlockEnabled)
        config.adBlockEnabled = true
        assertTrue(config.prefs.getBoolean(Config.ADBLOCK_ENABLED_PREF_KEY, false))
    }

    @Test
    fun theAddressOfSuggestionsStaysWithTheSiteOfItsSearchEngine() {
        assertTrue(config.searchSuggestions)
        assertEquals("", config.customSearchSuggestUrl)
        config.searchEngineURL.value = "https://search.example.com/?q=[query]"
        config.customSearchSuggestUrl = " https://ac.example.com/?q=[query] "
        assertEquals("https://ac.example.com/?q=[query]", config.customSearchSuggestUrl)
        //the same site with other parameters
        config.searchEngineURL.value = "https://Search.Example.com/find?text=[query]&lang=uk"
        assertEquals("https://ac.example.com/?q=[query]", config.customSearchSuggestUrl)
        //another engine (also set by the home page): the address is not its
        config.searchEngineURL.value = "https://other.example.org/?q=[query]"
        assertEquals("", config.customSearchSuggestUrl)
        config.searchEngineURL.value = Config.SearchEnginesURLs[0]
        assertEquals("", config.customSearchSuggestUrl)
        assertEquals("search.example.com", Config.searchEngineHost(" https://search.example.com:8080/?q=[query] "))
        assertNull(Config.searchEngineHost("not an address"))
        assertNull(Config.searchEngineHost(""))
    }

    @Test
    fun proxyIsUsedOnceItsAddressIsComplete() {
        assertNull(config.proxy)
        config.proxyEnabled = true
        config.proxyHost = "proxy.example.com"
        assertNull(config.proxy)
        config.proxyPort = 3128
        assertEquals(ProxySettings(ProxySettings.Type.HTTP, "proxy.example.com", 3128), config.proxy)
        config.proxyUsername = "user"
        config.proxyPassword = "secret"
        assertEquals("user" to "secret", config.proxy?.credentials)
        config.proxyType = ProxySettings.Type.SOCKS5
        assertNull(config.proxy?.credentials)
        config.proxyEnabled = false
        assertNull(config.proxy)
    }

    @Test
    fun proxyLoginIsNotInTheMainPrefs() {
        config.proxyPassword = "secret"
        assertFalse(config.prefs.contains(Config.PROXY_PASSWORD_KEY))
        assertEquals("secret", AppContext.get().getSharedPreferences(Config.CREDENTIALS_PREFS_NAME, 0)
            .getString(Config.PROXY_PASSWORD_KEY, null))
    }

    @Test
    fun proxyGoesToTheDownloadsProcessInABundle() {
        val proxy = ProxySettings(ProxySettings.Type.HTTP, "proxy.example.com", 3128, "user", "secret")
        assertEquals(proxy, ProxySettings.fromBundle(proxy.toBundle()))
        assertNull(ProxySettings.fromBundle(null))
        assertNull(ProxySettings.fromBundle(android.os.Bundle()))
    }
}
