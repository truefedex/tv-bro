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
                    val links = JSONArray()
                    for (item in callback.getHomePageLinks()) {
                        links.put(item.toJsonObj())
                    }
                    //the calls go to the page through the port: pages of the extension run no javascript: urls
                    if (Utils.isFireTV(AppContext.get())) {
                        port.postMessage(pageCall("hideVoiceSearchUI", JSONObject()))
                    }
                    port.postMessage(pageCall("renderLinks",
                        JSONObject().put("mode", cfg.homePageLinksMode.name).put("links", links)))
                    port.postMessage(pageCall("applySearchEngine",
                        JSONObject().put("engine", cfg.guessSearchEngineName()).put("url", cfg.searchEngineURL.value)))
                    //pages of older versions do not report it (or report it as null)
                    val data = msgJson.optJSONObject("data")
                    if (data != null && !data.isNull("searchBarX") && !data.isNull("searchBarY")) {
                        callback.onHomePageSearchBarLocated(data.getInt("searchBarX"), data.getInt("searchBarY"))
                    }
                }
                "setSearchEngine" -> {
                    val data = msgJson.getJSONObject("data")
                    val engine = data.getString("engine")
                    val customSearchEngineURL = data.getString("customSearchEngineURL")
                    AppContext.provideConfig().searchEngineURL.value = customSearchEngineURL
                }
                "onEditBookmark" -> {
                    val index = msgJson.getInt("data")
                    val callback = webEngine.callback ?: return
                    callback.getActivity().runOnUiThread { callback.onEditHomePageBookmarkSelected(index) }
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

    /**
     * A call of the page's function [name] with the named arguments in [data] (the port takes no arrays
     * of mixed types)
     */
    private fun pageCall(name: String, data: JSONObject): JSONObject =
        JSONObject().put("action", name).put("data", data)

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
        webEngine.appHomeContentScriptPortDelegate = null
    }

    companion object {
        val TAG: String = AppHomeContentScriptPortDelegate::class.java.simpleName
    }
}