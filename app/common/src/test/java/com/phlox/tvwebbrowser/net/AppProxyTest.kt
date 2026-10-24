package com.phlox.tvwebbrowser.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.URL

class AppProxyTest {
    private val http = ProxySettings(ProxySettings.Type.HTTP, "proxy.example.com", 8080, "user", "secret")
    private val socks = ProxySettings(ProxySettings.Type.SOCKS5, "10.1.2.3", 1080)

    @After
    fun tearDown() {
        AppProxy.settings = null
    }

    @Test
    fun localAddressesAreBypassed() {
        for (host in listOf("localhost", "app.localhost", "printer.local", "router", "127.0.0.1", "10.0.0.5",
                "172.16.1.1", "172.31.255.255", "192.168.0.1", "169.254.1.1", "::1", "[::1]", "fd12::1", "fe80::1",
                "LOCALHOST", "nas.local.")) {
            assertTrue(host, AppProxy.isBypassed(host))
        }
    }

    @Test
    fun otherAddressesGoThroughTheProxy() {
        for (host in listOf("example.com", "www.local-news.com", "8.8.8.8", "172.32.0.1", "172.15.0.1",
                "192.169.0.1", "2001:db8::1", "999.1.1.1", "1.2.3")) {
            assertFalse(host, AppProxy.isBypassed(host))
        }
    }

    @Test
    fun connectionsGetTheProxyOfTheSettings() {
        assertEquals(Proxy.NO_PROXY, AppProxy.proxyFor("example.com"))
        AppProxy.settings = http
        val proxy = AppProxy.proxyFor("example.com")
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("proxy.example.com", address.hostString)
        assertEquals(8080, address.port)
        assertEquals(Proxy.NO_PROXY, AppProxy.proxyFor("192.168.1.1"))
        assertEquals(Proxy.Type.SOCKS, AppProxy.proxyFor("example.com", socks).type())
    }

    @Test
    fun openGivesTheProxyToTheConnection() {
        AppProxy.settings = http
        //no request is made until the connection connects
        val connection = AppProxy.open(URL("http://example.com/"))
        assertTrue(connection.toString(), connection.url.host == "example.com")
    }

    @Test
    fun selectorProxiesOnlyWebRequests() {
        AppProxy.install()
        AppProxy.settings = http
        val selector = ProxySelector.getDefault()
        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://example.com/video.m3u8")).single().type())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("socket://192.168.0.7:5000")).single())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("socket://example.com:5000")).single())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("http://localhost:8000/")).single())
    }

    @Test
    fun authenticatorAnswersOnlyTheProxy() {
        AppProxy.install()
        AppProxy.proxyFor("example.com", http)
        val url = URL("https://example.com/")
        val answer = Authenticator.requestPasswordAuthentication("proxy.example.com", null, 8080, "https", "realm",
            "basic", url, Authenticator.RequestorType.PROXY)
        assertEquals("user", answer.userName)
        assertEquals("secret", String(answer.password))
        //the server of a site, another port
        assertNull(Authenticator.requestPasswordAuthentication("proxy.example.com", null, 8080, "https", "realm",
            "basic", url, Authenticator.RequestorType.SERVER))
        assertNull(Authenticator.requestPasswordAuthentication("proxy.example.com", null, 3128, "https", "realm",
            "basic", url, Authenticator.RequestorType.PROXY))
    }

    @Test
    fun refusedLoginIsNotGivenAgain() {
        AppProxy.install()
        AppProxy.proxyFor("example.com", http)
        fun ask(url: String) = Authenticator.requestPasswordAuthentication("proxy.example.com", null, 8080, "https",
            "realm", "basic", URL(url), Authenticator.RequestorType.PROXY)
        assertEquals("user", ask("https://refused.example.com/").userName)
        //the proxy answered the login with a 407 again
        assertNull(ask("https://refused.example.com/"))
        //another request, and the same one later, get it
        assertEquals("user", ask("https://other.example.com/").userName)
        assertEquals("user", ask("https://refused.example.com/").userName)
    }

    @Test
    fun onlyAnHttpProxyHasALogin() {
        assertEquals("user" to "secret", http.credentials)
        assertNull(socks.copy(username = "user", password = "secret").credentials)
        assertNull(ProxySettings.of(true, ProxySettings.Type.SOCKS5, "host", 1080, "user", "secret")!!.username)
        assertNull(ProxySettings.of(true, ProxySettings.Type.HTTP, "host", 3128, "", "secret")!!.credentials)
    }

    @Test
    fun aProxyNeedsAnAddressAndAPort() {
        assertNull(ProxySettings.of(false, ProxySettings.Type.HTTP, "host", 3128, "", ""))
        assertNull(ProxySettings.of(true, ProxySettings.Type.HTTP, "", 3128, "", ""))
        assertNull(ProxySettings.of(true, ProxySettings.Type.HTTP, "host", 0, "", ""))
        assertNull(ProxySettings.of(true, ProxySettings.Type.HTTP, "host", 70000, "", ""))
        assertNull(ProxySettings.of(true, ProxySettings.Type.HTTP, "http://host", 3128, "", ""))
        assertNull(ProxySettings.of(true, ProxySettings.Type.HTTP, "host/path", 3128, "", ""))
        assertNull(ProxySettings.of(true, ProxySettings.Type.HTTP, "192.168.1080", 3128, "", ""))
        assertNull(ProxySettings.of(true, ProxySettings.Type.HTTP, "10.0.0.256", 3128, "", ""))
        assertEquals("10.0.0.1", ProxySettings.of(true, ProxySettings.Type.HTTP, "10.0.0.1", 3128, "", "")?.host)
        assertEquals(ProxySettings(ProxySettings.Type.HTTP, "[2001:db8::1]", 3128),
            ProxySettings.of(true, ProxySettings.Type.HTTP, "[2001:db8::1]", 3128, "", ""))
    }

    @Test
    fun theAddressOfTheProxyIsToldApart() {
        assertTrue(http.isProxyAddress("PROXY.example.com"))
        assertFalse(http.isProxyAddress("proxy.example.com", 3128))
        assertFalse(http.isProxyAddress("example.com"))
        assertFalse(http.toString().contains("secret"))
    }
}
