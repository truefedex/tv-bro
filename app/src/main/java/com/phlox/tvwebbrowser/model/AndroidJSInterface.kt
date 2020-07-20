package com.phlox.tvwebbrowser.model

import android.content.Context
import android.net.http.SslError
import android.webkit.JavascriptInterface
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro

import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.MainActivityViewModel

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject


class AndroidJSInterface(private val mainActivityViewModel: MainActivityViewModel) {
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
        return mainActivityViewModel.currentTab.value?.currentOriginalUrl ?: ""
    }

    @JavascriptInterface
    fun navigateBack() {
        activity?.runOnUiThread { activity?.navigateBack(true) }
    }

    @JavascriptInterface
    fun reloadWithSslTrust() {
        activity?.runOnUiThread {
            mainActivityViewModel.currentTab.value?.apply {
                trustSsl = true
                currentOriginalUrl?.apply { webView?.loadUrl(this) }
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
            mainActivityViewModel.currentTab.value?.lastSSLError?.toString() ?: ""
        } else {
            when (mainActivityViewModel.currentTab.value?.lastSSLError?.primaryError) {
                SslError.SSL_EXPIRED -> TVBro.instance.getString(R.string.ssl_expired)
                SslError.SSL_IDMISMATCH -> TVBro.instance.getString(R.string.ssl_idmismatch)
                SslError.SSL_DATE_INVALID -> TVBro.instance.getString(R.string.ssl_date_invalid)
                SslError.SSL_INVALID -> TVBro.instance.getString(R.string.ssl_invalid)
                else -> "unknown"
            }
        }
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
