package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Clears the data of one site in WebView. Newer WebViews do it themselves for the whole site (the domain
 * the host belongs to, with its subdomains). Older ones have no removal of the cookies of one site: they
 * are expired one by one, the storage of the host's origins is deleted, and the whole cache (it holds no
 * data of the user) is cleared.
 */
internal object WebViewSiteData {
    suspend fun clear(context: Context, host: String) {
        val storage = WebStorage.getInstance()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            suspendCoroutine { cont ->
                WebStorageCompat.deleteBrowsingDataForSite(storage, host) { cont.resume(Unit) }
            }
            return
        }
        val cookies = CookieManager.getInstance()
        for (secure in listOf(true, false)) {
            val origin = (if (secure) "https://" else "http://") + host
            for (expiry in SiteCookies.expiries(host, secure, cookies.getCookie("$origin/"))) {
                cookies.setCookie("$origin/", expiry)
            }
            storage.deleteOrigin(origin)
        }
        cookies.flush()
        WebView(context).clearCache(true)
    }
}

/**
 * Cookies that expire the cookies of a host: WebView gives only their names and values, not their domains
 * and paths, so each name expires for the host and for each domain above it, on the path "/".
 */
internal object SiteCookies {
    private const val EXPIRED = "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
    private val IP_ADDRESS = Regex("""^[\d.]+$|:""")

    /** @param cookieHeader the cookies of the host as WebView gives them ("a=1; b=2"), null if none */
    fun expiries(host: String, secure: Boolean, cookieHeader: String?): List<String> {
        val names = cookieHeader.orEmpty().split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        //a host-only cookie has no domain, a domain cookie the host's or a domain above it
        val domains = listOf<String?>(null) + if (IP_ADDRESS.containsMatchIn(host)) emptyList() else
            host.split('.').let { labels -> (0..labels.size - 2).map { labels.drop(it).joinToString(".") } }
        val expiries = ArrayList<String>()
        for (name in names) {
            val prefixed = name.startsWith("__Secure-") || name.startsWith("__Host-")
            //such cookies come only over https
            if (prefixed && !secure) continue
            for (domain in domains) {
                //cookies of the __Host- prefix have no domain
                if (domain != null && name.startsWith("__Host-")) continue
                expiries += buildString {
                    append(name).append("=; ").append(EXPIRED)
                    if (domain != null) append("; Domain=").append(domain)
                    if (secure) append("; Secure")
                }
            }
        }
        return expiries
    }
}
