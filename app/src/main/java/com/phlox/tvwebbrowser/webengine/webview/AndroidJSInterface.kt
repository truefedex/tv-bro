package com.phlox.tvwebbrowser.webengine.webview

import android.net.http.SslError
import android.webkit.JavascriptInterface
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.DownloadUtils
import org.json.JSONArray


class AndroidJSInterface(private val webEngine: WebViewWebEngine) {
    @JavascriptInterface
    fun currentUrl(): String {
        if (!webEngine.tab.url.startsWith(WebViewEx.INTERNAL_SCHEME)) return ""
        return webEngine.tab.url
    }

    @JavascriptInterface
    fun reloadWithSslTrust() {
        val callback = webEngine.callback ?: return
        if ((webEngine.getView() as WebViewEx).currentOriginalUrl?.scheme != "file") return
        callback.getActivity().runOnUiThread {
            val webview = webEngine.getView() as? WebViewEx ?: return@runOnUiThread
            webview.trustSsl = true
            webEngine.tab.url.apply { webEngine.loadUrl(this) }
        }
    }

    @JavascriptInterface
    fun getStringByName(name: String): String {
        val ctx = TVBro.instance
        //val resId = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        //return ctx.getString(resId)
        when (name) {
            "connection_isnt_secure" -> return ctx.getString(R.string.connection_isnt_secure)
            "hostname" -> return ctx.getString(R.string.hostname)
            "err_desk" -> return ctx.getString(R.string.err_desk)
            "details" -> return ctx.getString(R.string.details)
            "back_to_safety" -> return ctx.getString(R.string.back_to_safety)
            "go_im_aware" -> return ctx.getString(R.string.go_im_aware)
            else -> return ""
        }
    }

    @JavascriptInterface
    fun startVoiceSearch() {
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { callback.initiateVoiceSearch() }
    }

    @JavascriptInterface
    fun setSearchEngine(engine: String, customSearchEngineURL: String) {
        if (!isHomePage()) return
        AppContext.provideConfig().searchEngineURL.value = customSearchEngineURL
    }

    @JavascriptInterface
    fun onEditBookmark(favoriteId: Long) {
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { callback.onEditHomePageBookmarkSelected(favoriteId) }
    }

    @JavascriptInterface
    fun onHomePageLoaded() {
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread {
            val cfg = AppContext.provideConfig()
            fun linksToJson(links: List<com.phlox.tvwebbrowser.model.HomePageLink>): String {
                val jsArr = JSONArray()
                for (item in links) {
                    jsArr.put(item.toJsonObj())
                }
                return jsArr.toString().replace("'", "\\'")
            }
            val bookmarks = linksToJson(callback.getHomePageLinks())
            val history = linksToJson(callback.getHomePageHistoryLinks())
            webEngine.evaluateJavascript("renderSections($bookmarks, $history)")
            webEngine.evaluateJavascript(
                "applySearchEngine(\"${cfg.guessSearchEngineName()}\", \"${cfg.searchEngineURL.value}\")")
        }
    }

    @JavascriptInterface
    fun lastSSLError(getDetails: Boolean): String {
        val lastSSLError = (webEngine.getView() as? WebViewEx)?.lastSSLError ?: return "unknown"
        return if (getDetails) {
            lastSSLError.toString()
        } else {
            when (lastSSLError.primaryError) {
                SslError.SSL_EXPIRED -> TVBro.instance.getString(R.string.ssl_expired)
                SslError.SSL_IDMISMATCH -> TVBro.instance.getString(R.string.ssl_idmismatch)
                SslError.SSL_DATE_INVALID -> TVBro.instance.getString(R.string.ssl_date_invalid)
                SslError.SSL_INVALID -> TVBro.instance.getString(R.string.ssl_invalid)
                else -> "unknown"
            }
        }
    }

    @JavascriptInterface
    fun takeBlobDownloadData(base64BlobData: String, fileName: String?, url: String, mimetype: String) {
        val callback = webEngine.callback ?: return
        val finalFileName = fileName ?: DownloadUtils.guessFileName(url, null, mimetype)
        callback.onDownloadRequested(url, "",
                finalFileName, "TV Bro",
            mimetype, Download.OperationAfterDownload.NOP, base64BlobData)
    }

    /**
     * Called from the injected script when the "play video in a player" shortcut
     * is pressed. domUrl is the source read from the page's <video> tag (empty if
     * there is none). It is evaluated together with the streams sniffed at the
     * network layer and the most suitable one is picked automatically.
     */
    @JavascriptInterface
    fun castMedia(domUrl: String) {
        val callback = webEngine.callback ?: return
        val webView = webEngine.getView() as? WebViewEx ?: return
        callback.getActivity().runOnUiThread { webView.castMedia(domUrl) }
    }

    @JavascriptInterface
    fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int) {
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { callback.markBookmarkRecommendationAsUseful(bookmarkOrder) }
    }

    private fun isHomePage(): Boolean {
        return Config.isHomePageUrl(webEngine.tab.url)
    }
}
