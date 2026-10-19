package com.phlox.tvwebbrowser.webengine.webview

import android.webkit.JavascriptInterface
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.utils.DownloadUtils
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import org.json.JSONArray


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
    fun onEditBookmark(index: Int) {
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { callback.onEditHomePageBookmarkSelected(index) }
    }

    /** The page reports where its search bar is (device pixels of the view) */
    @JavascriptInterface
    fun onHomePageLoaded(searchBarX: Int, searchBarY: Int) {
        onHomePageLoaded()
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { callback.onHomePageSearchBarLocated(searchBarX, searchBarY) }
    }

    @JavascriptInterface
    fun onHomePageLoaded() {
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread {
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

    @JavascriptInterface
    fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int) {
        if (!isHomePage()) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { callback.markBookmarkRecommendationAsUseful(bookmarkOrder) }
    }

    private fun isHomePage(): Boolean {
        return webEngine.tab.url == Config.HOME_PAGE_URL || webEngine.tab.url == Config.HOME_URL_ALIAS
    }
}
