package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.net.Uri
import android.util.Log
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * Answers the requests the background script of the extension holds (`webRequest.onBeforeRequest`):
 * each one waits for the answer (or the timeout of the script), so every request gets one.
 */
class AppWebExtensionBackgroundPortDelegate(val port: WebExtension.Port, val webEngine: GeckoWebEngine): WebExtension.PortDelegate {
    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        val msgJson = message as? JSONObject ?: return
        when (msgJson.optString("action")) {
            "onBeforeRequest" -> onBeforeRequest(msgJson.optJSONObject("details") ?: return, port)
        }
    }

    private fun onBeforeRequest(details: JSONObject, port: WebExtension.Port) {
        //the script keys the request by this id; without it there is nothing to answer
        val requestId = details.opt("requestId") ?: return
        var block = false
        try {
            block = isAd(details)
            if (block) webEngine.callback?.onBlockedAd(details.optString("url"))
        } catch (e: Exception) {
            Log.e(TAG, "onBeforeRequest", e)
        } finally {
            val data = JSONObject().put("requestId", requestId).put("block", block)
            port.postMessage(JSONObject().put("action", "onResolveRequest").put("data", data))
        }
    }

    private fun isAd(details: JSONObject): Boolean {
        //the callbacks are set only while the tab of this engine is current
        val callback = webEngine.callback ?: return false
        if (!callback.isAdBlockingEnabled()) return false
        val url = details.optString("url").ifEmpty { return false }
        //a request the user started (the address bar) has no origin
        val originUrl = details.optString("originUrl").ifEmpty { details.optString("documentUrl") }
        return callback.isAd(Uri.parse(url), details.optString("type"), Uri.parse(originUrl)) ?: false
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
        webEngine.appWebExtensionBackgroundPortDelegate = null
    }

    companion object {
        val TAG: String = AppWebExtensionBackgroundPortDelegate::class.java.simpleName
    }
}
