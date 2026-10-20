package com.phlox.tvwebbrowser.webengine.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopUserAgentTest {
    @Test
    fun phoneWebViewGetsTheDesktopChromeOfItsVersion() {
        val phone = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240105.004; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.102 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36", DesktopUserAgent.from(phone))
    }

    @Test
    fun tvWebViewGetsTheDesktopChromeOfItsVersion() {
        val tv = "Mozilla/5.0 (Linux; Android 9; SHIELD Android TV Build/PPR1.180610.011; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.179 Safari/537.36"
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36", DesktopUserAgent.from(tv))
    }

    @Test
    fun otherUserAgentsLoseOnlyThePhoneAndWebViewSigns() {
        assertEquals("Mozilla/5.0 (Linux; Model) AppleWebKit/600 Safari/600",
            DesktopUserAgent.from("Mozilla/5.0 (Linux; Android 7.1.2; Model; wv) AppleWebKit/600 Mobile Safari/600"))
    }
}
