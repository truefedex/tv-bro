package com.phlox.tvwebbrowser.webengine.webview

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.webengine.SiteView
import java.net.URI

/**
 * The User-Agent (and client hints) of each [SiteView] in WebView, built from the system one, so the Chrome version
 * stays current: Chrome of a desktop ([DesktopUserAgent]) or of an Android tablet ([TabletUserAgent]); the mobile
 * view is the system's own. The wide viewport of the desktop and tablet views for pages that ask for the width of
 * the device is written by [WebViewPageZoom] with the zoom of the page.
 */
internal class SiteViewUserAgent(private val webView: WebView) {
    /** The client hints of the system, kept before the first change */
    private var systemUserAgentMetadata: UserAgentMetadata? = null

    /**
     * @param userAgent the User-Agent the user chose in place of the one of the view, null for the one of the
     * view. The client hints still follow the view: WebView can not be told to drop them once they were set.
     */
    fun apply(view: SiteView, userAgent: String?) {
        val settings = webView.settings
        //the metadata of the system is kept before the User-Agent changes, which may change what WebView gives
        val metadataSupported = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        val system = if (!metadataSupported) null else systemUserAgentMetadata
            ?: WebSettingsCompat.getUserAgentMetadata(settings).also { systemUserAgentMetadata = it }
        //null gives the system User-Agent back
        settings.userAgentString = userAgent ?: when (view) {
            SiteView.DESKTOP -> DesktopUserAgent.from(systemUserAgent(webView))
            SiteView.TABLET -> TabletUserAgent.from(systemUserAgent(webView))
            SiteView.MOBILE -> null
        }
        if (system != null) {
            WebSettingsCompat.setUserAgentMetadata(settings, when (view) {
                SiteView.DESKTOP -> desktopMetadata(system)
                SiteView.TABLET -> tabletMetadata(system)
                SiteView.MOBILE -> system
            })
        }
    }

    /** What Chrome on an Android tablet gives: the platform of the system, not a phone, no model */
    private fun tabletMetadata(system: UserAgentMetadata): UserAgentMetadata {
        val builder = UserAgentMetadata.Builder(system)
            .setBrandVersionList(DesktopUserAgent.brands(system.brandVersionList))
            .setModel("")
            .setMobile(false)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
            builder.setFormFactors(listOf("Tablet"))
        }
        return builder.build()
    }

    private fun desktopMetadata(system: UserAgentMetadata): UserAgentMetadata {
        val builder = UserAgentMetadata.Builder(system)
            .setBrandVersionList(DesktopUserAgent.brands(system.brandVersionList))
            .setPlatform("Linux")
            .setPlatformVersion("")
            .setModel("")
            .setArchitecture("x86")
            .setBitness(64)
            .setMobile(false)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
            builder.setFormFactors(listOf("Desktop"))
        }
        return builder.build()
    }

    companion object {
        private var systemUserAgent: String? = null

        private fun systemUserAgent(webView: WebView): String =
            systemUserAgent ?: WebSettings.getDefaultUserAgent(webView.context).also { systemUserAgent = it }
    }
}

/**
 * Pages that always get the mobile view. Google refuses sign-in to WebView with a desktop User-Agent ("This
 * browser or app may not be secure"), even with fitting client hints: WebView sends the package of the app in
 * X-Requested-With, which no desktop browser does and current WebView versions do not let an app turn off.
 * The system User-Agent says it is WebView, and Google lets it sign in.
 */
internal object MobileViewPages {
    private val HOSTS = setOf("accounts.google.com")

    fun matches(url: String?): Boolean {
        if (url == null) return false
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return host.lowercase() in HOSTS
    }
}

/**
 * A desktop User-Agent built from the one of the system WebView: what Chrome on Android sends for its
 * desktop view, with the major version of the system Chrome.
 */
internal object DesktopUserAgent {
    private val CHROME_VERSION = Regex("""Chrome/(\d+)""")
    private const val WEBVIEW_BRAND = "Android WebView"
    private const val CHROME_BRAND = "Google Chrome"

    /**
     * The brands of the client hints (`Sec-CH-UA`, `navigator.userAgentData`) for the desktop view: those of
     * the system with Chrome in place of WebView, so they fit the desktop Chrome User-Agent. The order is kept: Chromium orders the
     * brands by its major version, as Chrome does.
     */
    fun brands(system: List<UserAgentMetadata.BrandVersion>): List<UserAgentMetadata.BrandVersion> =
        system.map {
            if (it.brand == WEBVIEW_BRAND) UserAgentMetadata.BrandVersion.Builder(it).setBrand(CHROME_BRAND).build()
            else it
        }

    fun from(systemUserAgent: String): String {
        val major = CHROME_VERSION.find(systemUserAgent)?.groupValues?.get(1)
        if (major != null) {
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$major.0.0.0 Safari/537.36"
        }
        //not Chromium: only the signs of a phone and of WebView go away
        return systemUserAgent
            .replace(Regex("""Android [\d.]+;\s*"""), "")
            .replace("; wv", "")
            .replace(" Mobile", "")
    }
}

/**
 * The User-Agent of the tablet view: what Chrome on an Android tablet sends (the reduced User-Agent of Chrome, without
 * "Mobile"), with the major version of the system Chrome. Sites mostly give it their desktop pages, and anti-bot
 * checks (Cloudflare Turnstile) take it, as it says Android as everything else of WebView does; they refuse a
 * desktop one.
 */
internal object TabletUserAgent {
    private val CHROME_VERSION = Regex("""Chrome/(\d+)""")

    fun from(systemUserAgent: String): String {
        val major = CHROME_VERSION.find(systemUserAgent)?.groupValues?.get(1)
        if (major != null) {
            return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$major.0.0.0 Safari/537.36"
        }
        //not Chromium: only the signs of a phone and of WebView go away
        return systemUserAgent
            .replace("; wv", "")
            .replace(" Mobile", "")
    }
}
