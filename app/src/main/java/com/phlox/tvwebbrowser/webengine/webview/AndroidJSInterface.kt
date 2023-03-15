package com.phlox.tvwebbrowser.webengine.webview

import android.net.http.SslError
import android.webkit.JavascriptInterface
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.MainActivityViewModel
import com.phlox.tvwebbrowser.activity.main.TabsModel
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.utils.DownloadUtils
import org.json.JSONArray


class AndroidJSInterface(val webEngine: WebViewWebEngine
/*private val activity: MainActivity,
                         private val mainActivityViewModel: MainActivityViewModel,
                         private val tabsModel: TabsModel,
                         private val tab: WebTabState
*/) {

    @JavascriptInterface
    fun navigate(url: String) {
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { webEngine.loadUrl(url) }
    }

    @JavascriptInterface
    fun currentUrl(): String {
        return webEngine.tab.url ?: ""
    }

    @JavascriptInterface
    fun navigateBack() {
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { webEngine.goBack() }
    }

    @JavascriptInterface
    fun reloadWithSslTrust() {
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread {
            val webview = webEngine.getView() as? WebViewEx ?: return@runOnUiThread
            webview.trustSsl = true
            webEngine.tab.url?.apply { webEngine.loadUrl(this) }
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
    fun homePageLinks(): String {
        if (webEngine.tab.url != Config.DEFAULT_HOME_URL) return "[]"
        val callback = webEngine.callback ?: return "[]"
        val jsArr = JSONArray()
        for (item in callback.getHomePageLinks()) {
            jsArr.put(item.toJsonObj())
        }
        return jsArr.toString()
    }

    @JavascriptInterface
    fun startVoiceSearch() {
        if (webEngine.tab.url != Config.DEFAULT_HOME_URL) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { callback.initiateVoiceSearch() }
    }

    @JavascriptInterface
    fun setSearchEngine(engine: String, customSearchEngineURL: String) {
        if (webEngine.tab.url != Config.DEFAULT_HOME_URL) return
        TVBro.config.searchEngineURL.value = customSearchEngineURL
    }

    @JavascriptInterface
    fun onEditBookmark(index: Int) {
        if (webEngine.tab.url != Config.DEFAULT_HOME_URL) return
        val callback = webEngine.callback ?: return
        callback.getActivity().runOnUiThread { callback.onEditHomePageBookmarkSelected(index) }
    }

    @JavascriptInterface
    fun homePageLinksMode(): String {
        return TVBro.config.homePageLinksMode.name
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
}
