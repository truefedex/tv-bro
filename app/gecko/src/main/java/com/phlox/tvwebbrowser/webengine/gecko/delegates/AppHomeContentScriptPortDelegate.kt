package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.io.ByteArrayOutputStream

class AppHomeContentScriptPortDelegate(val port: WebExtension.Port, val webEngine: GeckoWebEngine): WebExtension.PortDelegate {
    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        //Log.d(TAG, "onPortMessage: $message")
        try {
            val msgJson = message as JSONObject
            when (msgJson.getString("action")) {
                "startVoiceSearch" -> {
                    webEngine.callback?.getActivity()?.runOnUiThread {
                        webEngine.callback?.initiateVoiceSearch()
                    }
                }
                "onHomePageLoaded" -> {
                    val callback = webEngine.callback ?: return
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
                    if (Utils.isFireTV(AppContext.get())) {
                        webEngine.evaluateJavascript("hideVoiceSearchUI()")
                    }
                    webEngine.evaluateJavascript("renderSections($bookmarks, $history)")
                    webEngine.evaluateJavascript(
                        "applySearchEngine(\"${cfg.guessSearchEngineName()}\", \"${cfg.searchEngineURL.value}\")")
                }
                "setSearchEngine" -> {
                    val data = msgJson.getJSONObject("data")
                    val engine = data.getString("engine")
                    val customSearchEngineURL = data.getString("customSearchEngineURL")
                    AppContext.provideConfig().searchEngineURL.value = customSearchEngineURL
                }
                "onEditBookmark" -> {
                    val favoriteId = msgJson.getLong("data")
                    val callback = webEngine.callback ?: return
                    callback.getActivity().runOnUiThread { callback.onEditHomePageBookmarkSelected(favoriteId) }
                }
                "requestFavicon" -> {
                    val url = msgJson.getString("data")
                    (webEngine.callback?.getActivity() as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                        val favicon = FaviconsPool.get(url)
                        if (favicon != null) {
                            val msg = JSONObject()
                            msg.put("action", "favicon")
                            //put base64 encoded favicon
                            val faviconBytes = ByteArrayOutputStream()
                            favicon.compress(Bitmap.CompressFormat.PNG, 100, faviconBytes)
                            val faviconBase64 = "data:image/png;base64," + Base64.encodeToString(faviconBytes.toByteArray(), Base64.DEFAULT)
                            msg.put("data", JSONObject().put("url", url).put("data", faviconBase64))
                            port.postMessage(msg)
                        }
                    }
                }
                "markBookmarkRecommendationAsUseful" -> {
                    val bookmarkOrder = msgJson.getInt("data")
                    val callback = webEngine.callback ?: return
                    callback.markBookmarkRecommendationAsUseful(bookmarkOrder)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
        webEngine.appHomeContentScriptPortDelegate = null
    }

    companion object {
        val TAG: String = AppHomeContentScriptPortDelegate::class.java.simpleName
    }
}