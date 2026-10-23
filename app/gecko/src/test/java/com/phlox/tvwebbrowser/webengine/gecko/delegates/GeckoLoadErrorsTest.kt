package com.phlox.tvwebbrowser.webengine.gecko.delegates

import com.phlox.tvwebbrowser.webengine.LoadErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.geckoview.WebRequestError

class GeckoLoadErrorsTest {
    @Test
    fun securityErrorsLeadBack() {
        val kind = GeckoLoadErrors.kind(WebRequestError.ERROR_CATEGORY_SECURITY, WebRequestError.ERROR_SECURITY_BAD_CERT)
        assertEquals(LoadErrorKind.INSECURE, kind)
        assertEquals(true, kind.leadsBack)
    }

    @Test
    fun safeBrowsingErrorsAreDangerous() {
        assertEquals(LoadErrorKind.DANGEROUS, GeckoLoadErrors.kind(
            WebRequestError.ERROR_CATEGORY_SAFEBROWSING, WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI))
    }

    @Test
    fun crashedContentOffersReload() {
        val kind = GeckoLoadErrors.kind(WebRequestError.ERROR_CATEGORY_CONTENT, WebRequestError.ERROR_CONTENT_CRASHED)
        assertEquals(LoadErrorKind.CRASHED, kind)
        assertEquals(false, kind.leadsBack)
    }

    @Test
    fun networkAndProxyErrorsAreNetwork() {
        assertEquals(LoadErrorKind.NETWORK, GeckoLoadErrors.kind(
            WebRequestError.ERROR_CATEGORY_NETWORK, WebRequestError.ERROR_UNKNOWN_HOST))
        assertEquals(LoadErrorKind.NETWORK, GeckoLoadErrors.kind(
            WebRequestError.ERROR_CATEGORY_PROXY, WebRequestError.ERROR_PROXY_CONNECTION_REFUSED))
    }

    @Test
    fun otherErrorsAreGeneric() {
        assertEquals(LoadErrorKind.GENERIC, GeckoLoadErrors.kind(
            WebRequestError.ERROR_CATEGORY_URI, WebRequestError.ERROR_MALFORMED_URI))
        assertEquals(LoadErrorKind.GENERIC, GeckoLoadErrors.kind(
            WebRequestError.ERROR_CATEGORY_CONTENT, WebRequestError.ERROR_CORRUPTED_CONTENT))
    }
}
