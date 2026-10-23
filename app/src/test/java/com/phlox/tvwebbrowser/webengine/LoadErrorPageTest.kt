package com.phlox.tvwebbrowser.webengine

import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

/** The error page of both engines is filled from the template in the assets of :app:common */
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(qualifiers = "en")
class LoadErrorPageTest {
    private val context = AppContext.get()

    @Test
    fun networkErrorShowsTheHostAndOffersTryAgain() {
        val page = LoadErrorPage.create(context, LoadErrorKind.NETWORK,
            "https://www.example.com/search?q=a&b=<c>", "net::ERR_NAME_NOT_RESOLVED")
        assertTrue(page.contains("<body data-state=\"network\">"))
        assertTrue(page.contains("<h1>Can&#39;t reach this site</h1>"))
        assertTrue(page.contains("<dd id=\"hostname\">www.example.com</dd>"))
        assertTrue(page.contains(">net::ERR_NAME_NOT_RESOLVED</dd>"))
        assertTrue(page.contains("data-url=\"https://www.example.com/search?q=a&amp;b=&lt;c&gt;\""))
        assertTrue(page.contains("Try again"))
        assertTrue(page.contains("data-home=\"${Config.HOME_PAGE_URL}\""))
        assertFalse(page.contains("{{"))
    }

    @Test
    fun dangerousSiteLeadsBackToSafety() {
        val page = LoadErrorPage.create(context, LoadErrorKind.DANGEROUS, "https://bad.example/", "")
        assertTrue(page.contains("<body data-state=\"dangerous\">"))
        assertTrue(page.contains("Back to safety"))
    }

    @Test
    fun crashedPageOffersReload() {
        val page = LoadErrorPage.create(context, LoadErrorKind.CRASHED, "https://www.example.com/", "")
        assertTrue(page.contains("Reload"))
    }

    @Test
    fun pageWithoutUrlHasNoHost() {
        val page = LoadErrorPage.create(context, LoadErrorKind.GENERIC, null, "UNKNOWN")
        assertTrue(page.contains("<dd id=\"hostname\"></dd>"))
        assertTrue(page.contains("data-url=\"\""))
    }
}
