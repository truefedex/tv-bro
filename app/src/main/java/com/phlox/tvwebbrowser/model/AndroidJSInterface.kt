package com.phlox.tvwebbrowser.model

import android.net.http.SslError
import android.webkit.JavascriptInterface
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.MainActivityViewModel
import com.phlox.tvwebbrowser.activity.main.TabsModel
import com.phlox.tvwebbrowser.utils.DownloadUtils
import org.json.JSONArray


class AndroidJSInterface(private val activity: MainActivity,
                         private val mainActivityViewModel: MainActivityViewModel,
                         private val tabsModel: TabsModel,
                         private val tab: WebTabState) {

    @JavascriptInterface
    fun navigate(string: String) {
        activity.runOnUiThread { activity.navigate(string) }
    }

    @JavascriptInterface
    fun currentUrl(): String {
        return tab.url ?: ""
    }

    @JavascriptInterface
    fun navigateBack() {
        activity.runOnUiThread { activity.navigateBack(true) }
    }

    @JavascriptInterface
    fun reloadWithSslTrust() {
        activity.runOnUiThread {
            tab.webView?.trustSsl = true
            tab.url.apply { tab.webView?.loadUrl(this) }
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
        if (tab.url != Config.DEFAULT_HOME_URL) return "[]"
        val jsArr = JSONArray()
        for (item in mainActivityViewModel.homePageLinks) {
            jsArr.put(item.toJsonObj())
        }
        return jsArr.toString()
    }

    @JavascriptInterface
    fun startVoiceSearch() {
        if (tab.url != Config.DEFAULT_HOME_URL) return
        activity.runOnUiThread { activity.initiateVoiceSearch() }
    }

    @JavascriptInterface
    fun setSearchEngine(engine: String, customSearchEngineURL: String) {
        if (tab.url != Config.DEFAULT_HOME_URL) return
        TVBro.config.searchEngineURL.value = customSearchEngineURL
    }

    @JavascriptInterface
    fun onEditBookmark(index: Int) {
        if (tab.url != Config.DEFAULT_HOME_URL) return
        activity.runOnUiThread { activity.onEditHomePageBookmarkSelected(index) }
    }

    @JavascriptInterface
    fun homePageLinksMode(): String {
        return TVBro.config.homePageLinksMode.name
    }

    @JavascriptInterface
    fun lastSSLError(getDetails: Boolean): String {
        return if (getDetails) {
            tab.webView?.lastSSLError?.toString() ?: ""
        } else {
            when (tab.webView?.lastSSLError?.primaryError) {
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
        val finalFileName = fileName ?: DownloadUtils.guessFileName(url, null, mimetype)
        mainActivityViewModel.onDownloadRequested(activity, url, "",
                finalFileName, "TV Bro",
            mimetype, Download.OperationAfterDownload.NOP, base64BlobData)
    }
}
