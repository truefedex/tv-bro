package com.phlox.tvwebbrowser.webengine.webview

import android.webkit.WebViewClient
import com.phlox.tvwebbrowser.webengine.LoadErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebViewLoadErrorsTest {
    @Test
    fun connectionErrorsAreNetwork() {
        for (code in listOf(WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT,
                WebViewClient.ERROR_IO, WebViewClient.ERROR_PROXY_AUTHENTICATION)) {
            assertEquals(LoadErrorKind.NETWORK, WebViewLoadErrors.kind(code))
        }
    }

    @Test
    fun sslHandshakeLeadsBack() {
        assertEquals(LoadErrorKind.INSECURE, WebViewLoadErrors.kind(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE))
    }

    @Test
    fun unsafeResourceIsDangerous() {
        assertEquals(LoadErrorKind.DANGEROUS, WebViewLoadErrors.kind(WebViewClient.ERROR_UNSAFE_RESOURCE))
    }

    @Test
    fun safeBrowsingThreatsAreNamed() {
        assertEquals("SAFE_BROWSING_THREAT_PHISHING",
            WebViewLoadErrors.safeBrowsingThreatName(WebViewClient.SAFE_BROWSING_THREAT_PHISHING))
        assertEquals("SAFE_BROWSING_THREAT_MALWARE",
            WebViewLoadErrors.safeBrowsingThreatName(WebViewClient.SAFE_BROWSING_THREAT_MALWARE))
        assertEquals("SAFE_BROWSING_THREAT_UNKNOWN", WebViewLoadErrors.safeBrowsingThreatName(42))
    }

    @Test
    fun otherErrorsAreGeneric() {
        for (code in listOf(WebViewClient.ERROR_UNKNOWN, WebViewClient.ERROR_BAD_URL,
                WebViewClient.ERROR_UNSUPPORTED_SCHEME, WebViewClient.ERROR_REDIRECT_LOOP)) {
            assertEquals(LoadErrorKind.GENERIC, WebViewLoadErrors.kind(code))
        }
    }

    @Test
    fun replaceScriptWritesThePageAsAStringLiteral() {
        val page = "<p class=\"a\">It's\nhere</p><script>window.TVBRO_errorPage = true;</script>"
        val script = WebViewLoadErrors.replaceDocumentScript(page)
        assertTrue(script.startsWith("(function(){document.open();document.write(\""))
        assertTrue(script.contains("<p class=\\\"a\\\">It's\\nhere<\\/p>"))
        assertTrue(script.endsWith("return window.TVBRO_errorPage === true;})()"))
    }
}
