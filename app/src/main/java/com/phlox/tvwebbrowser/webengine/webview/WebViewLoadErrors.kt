package com.phlox.tvwebbrowser.webengine.webview

import android.webkit.WebViewClient
import com.phlox.tvwebbrowser.webengine.LoadErrorKind
import org.json.JSONObject

/**
 * The load errors of WebView as the error page shows them. WebView has no way to give its own error page
 * another look, so the error page is written over it when it is loaded (see [replaceDocumentScript])
 */
object WebViewLoadErrors {
    fun kind(errorCode: Int): LoadErrorKind = when (errorCode) {
        WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT,
        WebViewClient.ERROR_IO, WebViewClient.ERROR_PROXY_AUTHENTICATION -> LoadErrorKind.NETWORK
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> LoadErrorKind.INSECURE
        WebViewClient.ERROR_UNSAFE_RESOURCE -> LoadErrorKind.DANGEROUS
        else -> LoadErrorKind.GENERIC
    }

    /**
     * The error code the page shows for a Safe Browsing hit (WebView reports the load as `net::ERR_ABORTED`)
     */
    fun safeBrowsingThreatName(threatType: Int): String = when (threatType) {
        WebViewClient.SAFE_BROWSING_THREAT_MALWARE -> "SAFE_BROWSING_THREAT_MALWARE"
        WebViewClient.SAFE_BROWSING_THREAT_PHISHING -> "SAFE_BROWSING_THREAT_PHISHING"
        WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE -> "SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE"
        WebViewClient.SAFE_BROWSING_THREAT_BILLING -> "SAFE_BROWSING_THREAT_BILLING"
        else -> "SAFE_BROWSING_THREAT_UNKNOWN"
    }

    /** A script that puts [page] in the place of the document; it gives `true` if the scripts of [page] ran */
    fun replaceDocumentScript(page: String): String =
        "(function(){document.open();document.write(${JSONObject.quote(page)});document.close();" +
                "return window.TVBRO_errorPage === true;})()"
}
