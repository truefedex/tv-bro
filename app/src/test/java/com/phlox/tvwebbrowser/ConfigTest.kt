package com.phlox.tvwebbrowser

import com.phlox.tvwebbrowser.net.ProxySettings
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
    fun adBlockEnabledFollowsThePrefs() {
        //another process (incognito) writes the same prefs
        config.prefs.edit().putBoolean(Config.ADBLOCK_ENABLED_PREF_KEY, false).commit()
        assertFalse(config.adBlockEnabled)
        config.adBlockEnabled = true
        assertTrue(config.prefs.getBoolean(Config.ADBLOCK_ENABLED_PREF_KEY, false))
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
