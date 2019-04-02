package com.phlox.tvwebbrowser.model

import android.content.Context
import android.webkit.JavascriptInterface

import com.phlox.tvwebbrowser.activity.main.MainActivity

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Created by fedex on 11.12.16.
 */
class AndroidJSInterface {
    private var activity: MainActivity? = null
    private var suggestions = "[]"

    @JavascriptInterface
    fun search(string: String) {
        if (activity == null) return
        activity!!.runOnUiThread { activity!!.search(string) }
    }

    @JavascriptInterface
    fun navigate(string: String) {
        if (activity == null) return
        activity!!.runOnUiThread { activity!!.navigate(string) }
    }

    @JavascriptInterface
    fun suggestions(): String {
        return suggestions
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
