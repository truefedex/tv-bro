package com.phlox.tvwebbrowser.model

import android.content.Context
import android.net.http.SslError
import android.webkit.JavascriptInterface
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.MainActivityViewModel
import com.phlox.tvwebbrowser.activity.main.TabsModel
import com.phlox.tvwebbrowser.utils.DownloadUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject


class AndroidJSInterface(private val mainActivityViewModel: MainActivityViewModel, private val tabsModel: TabsModel) {
    private var activity: MainActivity? = null
    private var suggestions = "[]"

    @JavascriptInterface
    fun search(string: String) {
        activity?.runOnUiThread { activity?.search(string) }
    }

    @JavascriptInterface
    fun navigate(string: String) {
        activity?.runOnUiThread { activity?.navigate(string) }
    }

    @JavascriptInterface
    fun currentUrl(): String {
        return tabsModel.currentTab.value?.url ?: ""
    }

    @JavascriptInterface
    fun navigateBack() {
        activity?.runOnUiThread { activity?.navigateBack(true) }
    }

    @JavascriptInterface
    fun reloadWithSslTrust() {
        activity?.runOnUiThread {
            tabsModel.currentTab.value?.apply {
                webView?.trustSsl = true
                url?.apply { webView?.loadUrl(this) }
            }
        }
    }

    @JavascriptInterface
    fun getStringByName(name: String): String {
        val ctx = TVBro.instance
        val resId = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        return ctx.getString(resId)
    }

    @JavascriptInterface
    fun suggestions(): String {
        return suggestions
    }

    @JavascriptInterface
    fun lastSSLError(getDetails: Boolean): String {
        return if (getDetails) {
            tabsModel.currentTab.value?.webView?.lastSSLError?.toString() ?: ""
        } else {
            when (tabsModel.currentTab.value?.webView?.lastSSLError?.primaryError) {
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
        val activity = this.activity ?: return
        val finalFileName = fileName ?: DownloadUtils.guessFileName(url, null, mimetype)
        mainActivityViewModel.onDownloadRequested(activity, url, "",
                finalFileName, "TV Bro",
            mimetype, Download.OperationAfterDownload.NOP, base64BlobData)
    }

    fun setActivity(activity: MainActivity?) {
        this.activity = activity
    }

    @Throws(JSONException::class)
    fun setSuggestions(context: Context, frequentlyUsedURLs: List<HistoryItem>) {
        val jsArr = JSONArray()
        for (item in frequentlyUsedURLs) {
            val jsObj = JSONObject()
            jsObj.put("url", item.url)
            jsObj.put("title", item.title)
            if (item.favicon != null) {
                jsObj.put("favicon", "file:///" + context.cacheDir.absolutePath + "/favicons/" + item.favicon)
            }
            jsArr.put(jsObj)
        }
        suggestions = jsArr.toString()
    }
}
