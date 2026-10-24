package com.phlox.tvwebbrowser.webengine.gecko

import com.phlox.tvwebbrowser.net.ProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoProxyPrefsTest {
    @Test
    fun httpProxyTakesHttpAndSecureSites() {
        val prefs = GeckoProxyPrefs.prefsFor(ProxySettings(ProxySettings.Type.HTTP, "proxy.example.com", 3128, "u", "p"))
        assertEquals(1, prefs["network.proxy.type"])
        assertEquals("proxy.example.com", prefs["network.proxy.http"])
        assertEquals(3128, prefs["network.proxy.http_port"])
        assertEquals("proxy.example.com", prefs["network.proxy.ssl"])
        assertEquals(3128, prefs["network.proxy.ssl_port"])
        assertEquals("", prefs["network.proxy.socks"])
        assertEquals(false, prefs["network.proxy.failover_direct"])
    }

    @Test
    fun socksProxyResolvesNamesItself() {
        val prefs = GeckoProxyPrefs.prefsFor(ProxySettings(ProxySettings.Type.SOCKS5, "[2001:db8::1]", 1080))
        assertEquals("", prefs["network.proxy.http"])
        assertEquals("", prefs["network.proxy.ssl"])
        assertEquals("2001:db8::1", prefs["network.proxy.socks"])
        assertEquals(1080, prefs["network.proxy.socks_port"])
        assertEquals(5, prefs["network.proxy.socks_version"])
        assertEquals(true, prefs["network.proxy.socks_remote_dns"])
    }

    @Test
    fun localAddressesOpenDirectly() {
        val bypass = GeckoProxyPrefs.prefsFor(ProxySettings(ProxySettings.Type.HTTP, "h", 1))["network.proxy.no_proxies_on"]
            as String
        for (rule in listOf("localhost", ".local", "192.168.0.0/16", "fe80::/10", "<local>")) {
            assertTrue(rule, bypass.split(", ").contains(rule))
        }
    }
}
