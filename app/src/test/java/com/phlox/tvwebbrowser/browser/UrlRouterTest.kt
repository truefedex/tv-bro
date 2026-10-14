package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.browser.UrlRouter.UrlRoute
import com.phlox.tvwebbrowser.webengine.webview.WebViewEx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UrlRouterTest {
    private fun route(url: String, needsLocalNetworkPermission: Boolean = false) =
        UrlRouter.route(url) { needsLocalNetworkPermission }

    @Test
    fun webUrlsAreLoaded() {
        assertEquals(UrlRoute.LOAD, route("http://example.com"))
        assertEquals(UrlRoute.LOAD, route("https://example.com/path?q=1#a"))
        assertEquals(UrlRoute.LOAD, route(Config.HOME_PAGE_URL))
    }

    @Test
    fun browserSchemesAreLoaded() {
        assertEquals(UrlRoute.LOAD, route(Config.HOME_URL_ALIAS))
        assertEquals(UrlRoute.LOAD, route("about:blank"))
        assertEquals(UrlRoute.LOAD, route("data:text/html,<b>hi</b>"))
        assertEquals(UrlRoute.LOAD, route("blob:https://example.com/0d5c6c4e-7c1a-4b8e-9a43-3e0e2f1c5b9a"))
        assertEquals(UrlRoute.LOAD, route("javascript:void(0)"))
        assertEquals(UrlRoute.LOAD, route("JavaScript:void(0)"))
    }

    @Test
    fun intentUrlsAreOpenedAsIntents() {
        assertEquals(UrlRoute.OPEN_INTENT_URI, route("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"))
    }

    @Test
    fun otherSchemesGoToExternalApps() {
        assertEquals(UrlRoute.OPEN_IN_EXTERNAL_APP, route("market://details?id=com.phlox.tvwebbrowser"))
        assertEquals(UrlRoute.OPEN_IN_EXTERNAL_APP, route("mailto:someone@example.com"))
        // current behavior: only http(s) count as network urls
        assertEquals(UrlRoute.OPEN_IN_EXTERNAL_APP, route("ftp://example.com"))
        assertEquals(UrlRoute.OPEN_IN_EXTERNAL_APP, route("file:///sdcard/a.html"))
    }

    // current behavior: WebViewEx loads its internal pages bypassing this check, but if such url
    // gets here (for example as a restored tab url) it is treated as an external app url
    @Test
    fun internalUrlsGoToExternalApps() {
        val url = WebViewEx.INTERNAL_SCHEME + WebViewEx.INTERNAL_SCHEME_WARNING_DOMAIN +
                "?type=" + WebViewEx.INTERNAL_SCHEME_WARNING_DOMAIN_TYPE_CERT + "&url=https%3A%2F%2Fexample.com"
        assertEquals(UrlRoute.OPEN_IN_EXTERNAL_APP, route(url))
    }

    @Test
    fun urlsWithoutSchemeAreBlocked() {
        assertEquals(UrlRoute.BLOCK, route("example.com"))
        assertEquals(UrlRoute.BLOCK, route("/relative/path"))
        assertEquals(UrlRoute.BLOCK, route(""))
    }

    @Test
    fun localNetworkPermissionIsRequestedFirst() {
        assertEquals(UrlRoute.REQUEST_LOCAL_NETWORK_PERMISSION, route("http://192.168.0.1", needsLocalNetworkPermission = true))
        assertEquals(UrlRoute.REQUEST_LOCAL_NETWORK_PERMISSION, route("intent://x#Intent;end", needsLocalNetworkPermission = true))
    }

    @Test
    fun localNetworkCheckIsSkippedForUrlsWithoutScheme() {
        var checked = false
        val result = UrlRouter.route("192.168.0.1") { checked = true; true }
        assertEquals(UrlRoute.BLOCK, result)
        assertFalse(checked)
    }

    @Test
    fun localNetworkCheckGetsTheUrl() {
        var checkedUrl: String? = null
        UrlRouter.route("http://192.168.0.1/") { checkedUrl = it; false }
        assertEquals("http://192.168.0.1/", checkedUrl)
    }
}
