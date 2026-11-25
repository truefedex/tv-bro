package com.phlox.tvwebbrowser.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsSettingsTest {
    @Test
    fun theDeviceHasNoServer() {
        assertNull(DnsSettings.of(DnsSettings.Provider.SYSTEM, "https://dns.example.com/dns-query"))
    }

    @Test
    fun providersHaveAnAddressAndTheIpOfTheirServer() {
        for (provider in DnsSettings.Provider.entries) {
            if (provider == DnsSettings.Provider.SYSTEM || provider == DnsSettings.Provider.CUSTOM) continue
            val dns = DnsSettings.of(provider, "")
            assertNotNull(provider.name, dns)
            assertTrue(provider.name, DnsSettings.isValidUrl(dns!!.url))
            assertNotNull(provider.name, dns.bootstrapAddress)
            assertNotNull(provider.name, provider.title)
        }
        assertEquals(DnsSettings("https://dns.google/dns-query", "8.8.8.8"),
            DnsSettings.of(DnsSettings.Provider.GOOGLE, "https://other.example/dns-query"))
    }

    @Test
    fun theCustomServerIsItsAddressOnceItIsOne() {
        assertEquals(DnsSettings("https://dns.example.com/dns-query", null),
            DnsSettings.of(DnsSettings.Provider.CUSTOM, "  https://dns.example.com/dns-query "))
        assertNull(DnsSettings.of(DnsSettings.Provider.CUSTOM, ""))
        assertNull(DnsSettings.of(DnsSettings.Provider.CUSTOM, "dns.example.com"))
    }

    @Test
    fun onlyHttpsAddressesWithAHostAreServers() {
        assertTrue(DnsSettings.isValidUrl("https://dns.example.com/dns-query"))
        assertTrue(DnsSettings.isValidUrl("HTTPS://1.1.1.1/dns-query"))
        assertTrue(DnsSettings.isValidUrl("https://dns.example.com:8443/dns-query"))
        assertFalse(DnsSettings.isValidUrl("http://dns.example.com/dns-query"))
        assertFalse(DnsSettings.isValidUrl("tls://dns.example.com"))
        assertFalse(DnsSettings.isValidUrl("dns.example.com"))
        assertFalse(DnsSettings.isValidUrl("https:///dns-query"))
        assertFalse(DnsSettings.isValidUrl("https://dns.example.com/dns query"))
        assertFalse(DnsSettings.isValidUrl("1.1.1.1"))
    }
}
