package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.net.DnsSettings
import com.phlox.tvwebbrowser.net.ProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkSetupTest {
    private val dns = DnsSettings("https://dns.google/dns-query", "8.8.8.8")

    @Test
    fun theDnsIsUsedWithoutAProxy() {
        assertEquals(dns, NetworkSetup.dnsInUse(null, dns))
        assertNull(NetworkSetup.dnsInUse(null, null))
    }

    @Test
    fun aProxyFindsTheSitesItself() {
        assertNull(NetworkSetup.dnsInUse(ProxySettings(ProxySettings.Type.SOCKS5, "10.1.2.3", 1080), dns))
        assertNull(NetworkSetup.dnsInUse(ProxySettings(ProxySettings.Type.HTTP, "proxy.example.com", 3128), dns))
    }
}
