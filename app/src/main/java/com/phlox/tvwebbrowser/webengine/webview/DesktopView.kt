package com.phlox.tvwebbrowser.webengine.webview

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The desktop view of WebView: a desktop User-Agent (and client hints) built from the system one, so the
 * Chrome version stays current, and a wide viewport for pages that ask for the width of the device.
 * The mobile view is the system's own.
 */
internal class DesktopView(private val webView: WebView) {
    private var viewportScript: ScriptHandler? = null
    /** The client hints of the system, kept before the first change */
    private var systemUserAgentMetadata: UserAgentMetadata? = null

    fun apply(desktop: Boolean) {
        val settings = webView.settings
        //null gives the system User-Agent back
        settings.userAgentString = if (desktop) DesktopUserAgent.from(systemUserAgent(webView)) else null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val system = systemUserAgentMetadata ?: WebSettingsCompat.getUserAgentMetadata(settings).also {
                systemUserAgentMetadata = it
            }
            WebSettingsCompat.setUserAgentMetadata(settings, if (desktop) desktopMetadata(system) else system)
        }
        viewportScript?.remove()
        viewportScript = null
        if (desktop && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            viewportScript = WebViewCompat.addDocumentStartJavaScript(webView, WIDE_VIEWPORT_SCRIPT, setOf("*"))
        }
    }

    private fun desktopMetadata(system: UserAgentMetadata): UserAgentMetadata {
        val builder = UserAgentMetadata.Builder(system)
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

        /**
         * Pages that set their viewport to the width of the device get the width of a desktop page, as in
         * the desktop view of Chrome. Screens about as wide as that (TVs) keep the viewports of pages.
         */
        private const val WIDE_VIEWPORT_SCRIPT = """(function () {
  var WIDE = 'width=980';
  if (screen.width >= 960) return;
  function widen() {
    var metas = document.querySelectorAll('meta[name="viewport" i]');
    for (var i = 0; i < metas.length; i++) {
      if (metas[i].getAttribute('content') !== WIDE) metas[i].setAttribute('content', WIDE);
    }
  }
  var options = {childList: true, subtree: true, attributes: true, attributeFilter: ['content']};
  var observer = new MutationObserver(widen);
  observer.observe(document, options);
  document.addEventListener('DOMContentLoaded', function () {
    widen();
    observer.disconnect();
    if (document.head) observer.observe(document.head, options);
  });
})();"""
    }
}

/**
 * A desktop User-Agent built from the one of the system WebView: what Chrome on Android sends for its
 * desktop view, with the major version of the system Chrome.
 */
internal object DesktopUserAgent {
    private val CHROME_VERSION = Regex("""Chrome/(\d+)""")

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
