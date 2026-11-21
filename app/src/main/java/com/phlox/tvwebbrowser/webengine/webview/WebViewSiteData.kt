package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.webengine.SiteDataKind
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Clears the data of one site in WebView. Newer WebViews clear all of it themselves for the whole site (the
 * domain the host belongs to, with its subdomains), but only all at once. Otherwise each kind is cleared
 * apart: WebView has no removal of the cookies of one site, so they are expired one by one; the storage of
 * the origins of the host and its subdomains is deleted; and the whole cache (it holds no data of the user)
 * of the profile is cleared, as WebView has no cache of one site. All of it in the profile of WebView of the
 * tabs shown ([WebViewProfiles]).
 */
internal object WebViewSiteData {
    suspend fun clear(context: Context, host: String, kinds: Set<SiteDataKind>, profile: String?) {
        val storage = WebViewProfiles.webStorage(profile)
        if (kinds.containsAll(SiteDataKind.ALL) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            suspendCoroutine { cont ->
                WebStorageCompat.deleteBrowsingDataForSite(storage, host) { cont.resume(Unit) }
            }
            return
        }
        val hostOrigins = listOf("https://$host", "http://$host")
        if (SiteDataKind.COOKIES in kinds) {
            val cookies = WebViewProfiles.cookieManager(profile)
            for (origin in hostOrigins) {
                val secure = origin.startsWith("https:")
                for (expiry in SiteCookies.expiries(host, secure, cookies.getCookie("$origin/"))) {
                    cookies.setCookie("$origin/", expiry)
                }
            }
            cookies.flush()
        }
        if (SiteDataKind.STORAGE in kinds) {
            //getOrigins lists the origins with quota managed storage; local storage alone may be missing there
            val origins = LinkedHashSet(hostOrigins)
            origins += SiteOrigins.ofHost(host, storageOrigins(storage))
            for (origin in origins) storage.deleteOrigin(origin)
        }
        if (SiteDataKind.CACHE in kinds) {
            WebViewProfiles.bareView(context, profile).apply { clearCache(true); destroy() }
        }
    }

    private suspend fun storageOrigins(storage: WebStorage): Collection<String> = suspendCoroutine { cont ->
        storage.getOrigins { origins ->
            cont.resume(origins?.values?.mapNotNull { (it as? WebStorage.Origin)?.origin } ?: emptyList())
        }
    }
}

internal object SiteOrigins {
    /** The [origins] ("https://a.com:8080") of [host] and its subdomains */
    fun ofHost(host: String, origins: Collection<String>): List<String> = origins.filter { origin ->
        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return@filter false
        originHost.equals(host, ignoreCase = true) || originHost.endsWith(".$host", ignoreCase = true)
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
