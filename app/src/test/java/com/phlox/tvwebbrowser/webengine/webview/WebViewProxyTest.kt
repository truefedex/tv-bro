package com.phlox.tvwebbrowser.webengine.webview

import com.phlox.tvwebbrowser.net.ProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The rules only: WebView checks them itself, on a device */
@RunWith(RobolectricTestRunner::class)
class WebViewProxyTest {
    @Test
    fun everythingButLocalAddressesGoesThroughTheProxy() {
        val config = WebViewProxy.configOf(ProxySettings(ProxySettings.Type.HTTP, "proxy.example.com", 3128, "u", "p"))
        //no DIRECT rule: a proxy that fails gives an error page
        assertEquals(listOf("http://proxy.example.com:3128"), config.proxyRules.map { it.url })
        for (rule in listOf("<local>", "localhost", "*.local", "10.0.0.0/8", "fc00::/7")) {
            assertTrue(rule, config.bypassRules.contains(rule))
        }
    }

    @Test
    fun socksProxyOfAnIpv6Address() {
        val config = WebViewProxy.configOf(ProxySettings(ProxySettings.Type.SOCKS5, "2001:db8::1", 1080))
        assertEquals(listOf("socks5://[2001:db8::1]:1080"), config.proxyRules.map { it.url })
    }
}
