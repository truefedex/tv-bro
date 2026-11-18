package com.phlox.tvwebbrowser.webengine.webview

import android.webkit.JavascriptInterface
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.utils.DownloadUtils
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import com.phlox.tvwebbrowser.webengine.WebEngineCallbacks
import org.json.JSONArray
import org.json.JSONObject


class AndroidJSInterface(private val webEngine: WebViewWebEngine) {
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
    fun startVoiceSearch() {
        onHomePage { callback -> callback.initiateVoiceSearch() }
    }

    @JavascriptInterface
    fun setSearchEngine(engine: String, customSearchEngineURL: String) {
        onHomePage {
            val cfg = AppContext.provideConfig()
            cfg.setSearchEngine(Config.searchEngineUrlOfHomePage(engine, customSearchEngineURL))
            //the page searches with the address it keeps: the one of the app has the partner ID of Qwant
            val name = JSONObject.quote(cfg.guessSearchEngineName())
            val url = JSONObject.quote(cfg.searchEngineURL.value)
            webEngine.evaluateJavascript("applySearchEngine($name, $url)")
        }
    }

    @JavascriptInterface
    fun onEditBookmark(index: Int) {
        onHomePage { callback -> callback.onEditHomePageBookmarkSelected(index) }
    }

    /** The page reports where its search bar is (device pixels of the view) */
    @JavascriptInterface
    fun onHomePageLoaded(searchBarX: Int, searchBarY: Int) {
        onHomePageLoaded()
        onHomePage { callback -> callback.onHomePageSearchBarLocated(searchBarX, searchBarY) }
    }

    @JavascriptInterface
    fun onHomePageLoaded() {
        onHomePage { callback ->
            val cfg = AppContext.provideConfig()
            val jsArr = JSONArray()
            for (item in callback.getHomePageLinks()) {
                jsArr.put(item.toJsonObj())
            }
            var links = jsArr.toString()
            links = links.replace("'", "\\'")
            webEngine.evaluateJavascript("renderLinks('${cfg.homePageLinksMode.name}', $links)")
            webEngine.evaluateJavascript(
                "applySearchEngine(\"${cfg.guessSearchEngineName()}\", \"${cfg.searchEngineURL.value}\")")
        }
    }

    @JavascriptInterface
    fun takeBlobDownloadData(base64BlobData: String, fileName: String?, url: String, mimetype: String) {
        val callback = webEngine.callback ?: return
        val finalFileName = fileName ?: DownloadUtils.guessFileName(url, null, mimetype)
        callback.onDownloadRequested(DownloadRequest(url = url, referer = webEngine.tab.url, userAgent = "TV Bro",
            fileName = finalFileName, mimeType = mimetype, base64BlobData = base64BlobData))
    }

    /**
     * Runs [action] on the main thread if the page of the view is the home page. Calls of the page come on a
     * thread of WebView, and a page loaded from the cache (going back to it while another page loads) calls
     * before the main thread has its onPageStarted, so `tab.url` still had the page before it then. The url of
     * the view is the page that committed.
     */
    private inline fun onHomePage(crossinline action: (WebEngineCallbacks) -> Unit) {
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread {
            val current = webEngine.callback ?: return@runOnUiThread
            if (isHomePage()) action(current)
        }
    }

    private fun isHomePage(): Boolean {
        val url = webEngine.url ?: webEngine.tab.url
        return url == Config.HOME_PAGE_URL || url == Config.HOME_URL_ALIAS
    }
}
