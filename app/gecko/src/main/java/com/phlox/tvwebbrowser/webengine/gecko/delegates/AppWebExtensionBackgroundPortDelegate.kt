package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.net.Uri
import android.util.Log
import com.phlox.tvwebbrowser.player.MediaDiscoveryNotifier
import com.phlox.tvwebbrowser.player.MediaRequestCatalog
import com.phlox.tvwebbrowser.webengine.gecko.BrowserMediaBridge
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

class AppWebExtensionBackgroundPortDelegate(val port: WebExtension.Port, val webEngine: GeckoWebEngine): WebExtension.PortDelegate {
    init { BrowserMediaBridge.connect(port) }
    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        //Log.d(TAG, "onPortMessage: $message")
        try {
            val msgJson = message as JSONObject
            when (msgJson.getString("action")) {
                "activeMediaTab" -> BrowserMediaBridge.receive(msgJson)
                "mediaNavigation" -> {
                    val tabId = msgJson.getInt("tabId")
                    val url = msgJson.getString("url")
                    val removed = MediaRequestCatalog.shared.navigateTab(tabId, url)
                    Log.d(TAG, "mediaNavigation tabId=$tabId url=$url removed=$removed")
                }
                "mediaSkipped" -> {
                    val data = msgJson.getJSONObject("details")
                    Log.d(TAG, "mediaSkipped reason=${data.getString("reason")} tabId=${data.getInt("tabId")} " +
                        "status=${data.getInt("statusCode")} method=${data.getString("method")} url=${data.getString("url")}")
                }
                "mediaResponse" -> {
                    val data = msgJson.getJSONObject("details")
                    val headers = data.optJSONObject("headers") ?: JSONObject()
                    val url = data.getString("url")
                    MediaRequestCatalog.shared.observe(MediaRequestCatalog.Request(
                        url = url, page = data.getString("page"), frame = data.getString("frame"),
                        privateMode = data.optBoolean("privateMode"),
                        kind = MediaRequestCatalog.inferKind(url, data.optString("mime")),
                        headers = headers.keys().asSequence().associateWith { headers.getString(it) },
                        tabId = data.optInt("tabId", -1)
                    ))
                    // Segment files churn constantly; only a manifest/single file changes the list.
                    if (!url.substringBefore('?').lowercase().endsWith(".ts")) {
                        Log.d(TAG, "mediaObserved url=$url page=${data.optString("page")} tabId=${data.optInt("tabId", -1)}")
                        MediaDiscoveryNotifier.notifyChanged()
                    }
                }
                "onBeforeRequest" -> {
                    val data = msgJson.getJSONObject("details")
                    val requestId = data.getInt("requestId")
                    val url = data.getString("url")
                    val originUrl = data.optString("originUrl", "")
                    val type = data.getString("type")
                    val callback = webEngine.callback ?: return
                    val msg = JSONObject()
                    msg.put("action", "onResolveRequest")
                    val block = if (callback.isAdBlockingEnabled()) {
                        callback.isAd(Uri.parse(url), type, Uri.parse(originUrl)) ?: false
                    } else {
                        false
                    }
                    if (block) {
                        callback.onBlockedAd(url)
                    }
                    msg.put("data", JSONObject().put("requestId", requestId).put("block", block))
                    port.postMessage(msg)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        BrowserMediaBridge.disconnect(port)
        Log.d(TAG, "onDisconnect")
        webEngine.appHomeContentScriptPortDelegate = null
    }

    companion object {
        val TAG: String = AppWebExtensionBackgroundPortDelegate::class.java.simpleName
    }
}