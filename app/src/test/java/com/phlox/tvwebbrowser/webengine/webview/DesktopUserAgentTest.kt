package com.phlox.tvwebbrowser.webengine.webview

import androidx.webkit.UserAgentMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun brandsNameChromeInPlaceOfWebViewInTheSameOrder() {
        fun brand(name: String, major: String, full: String) =
            UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(major).setFullVersion(full).build()
        val system = listOf(
            brand("Not:A-Brand", "8", "8.0.0.0"),
            brand("Chromium", "156", "156.0.8078.47"),
            brand("Android WebView", "156", "156.0.8078.47"),
        )
        assertEquals(listOf(
            brand("Not:A-Brand", "8", "8.0.0.0"),
            brand("Chromium", "156", "156.0.8078.47"),
            brand("Google Chrome", "156", "156.0.8078.47"),
        ), DesktopUserAgent.brands(system))
    }

    @Test
    fun googleSignInPagesGetTheMobileView() {
        assertTrue(MobileViewPages.matches("https://accounts.google.com/v3/signin/identifier?continue=x"))
        assertTrue(MobileViewPages.matches("https://Accounts.Google.com/"))
        assertFalse(MobileViewPages.matches("https://www.google.com/search?q=accounts.google.com"))
        assertFalse(MobileViewPages.matches("https://myaccounts.google.com.example/"))
        assertFalse(MobileViewPages.matches("about:blank"))
        assertFalse(MobileViewPages.matches("not a url"))
        assertFalse(MobileViewPages.matches(null))
    }

    @Test
    fun otherUserAgentsLoseOnlyThePhoneAndWebViewSigns() {
        assertEquals("Mozilla/5.0 (Linux; Model) AppleWebKit/600 Safari/600",
            DesktopUserAgent.from("Mozilla/5.0 (Linux; Android 7.1.2; Model; wv) AppleWebKit/600 Mobile Safari/600"))
    }
}
