package com.phlox.tvwebbrowser.webengine.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteCookiesTest {
    private val expired = "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"

    @Test
    fun eachCookieExpiresForTheHostAndTheDomainsAboveIt() {
        assertEquals(listOf(
            "sid=; $expired; Secure",
            "sid=; $expired; Domain=www.example.com; Secure",
            "sid=; $expired; Domain=example.com; Secure",
            "lang=; $expired; Secure",
            "lang=; $expired; Domain=www.example.com; Secure",
            "lang=; $expired; Domain=example.com; Secure"
        ), SiteCookies.expiries("www.example.com", secure = true, cookieHeader = "sid=1; lang=en; sid=2"))
    }

    @Test
    fun prefixedCookiesExpireOnlyOverHttps() {
        assertEquals(listOf(
            "__Host-a=; $expired; Secure",
            "__Secure-b=; $expired; Secure",
            "__Secure-b=; $expired; Domain=example.com; Secure"
        ), SiteCookies.expiries("example.com", secure = true, cookieHeader = "__Host-a=1; __Secure-b=2"))
        assertEquals(emptyList<String>(),
            SiteCookies.expiries("example.com", secure = false, cookieHeader = "__Host-a=1; __Secure-b=2"))
    }

    @Test
    fun addressesHaveNoDomainsAndNoCookiesGiveNothing() {
        assertEquals(listOf("a=; $expired"), SiteCookies.expiries("192.168.1.2", secure = false, cookieHeader = "a=1"))
        assertEquals(emptyList<String>(), SiteCookies.expiries("example.com", secure = true, cookieHeader = null))
    }
}
