package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.net.Uri
import android.util.Log
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * The port of the background script of the extension, one for all tabs: it answers the requests the script
 * holds (`webRequest.onBeforeRequest`), each of which waits for the answer or the timeout of the script.
 * The decision is the app's ([WebEngineFactory.adBlocker]), with the site setting of the tab's page, so it
 * needs no engine; the script tells the tab about what was blocked itself.
 */
class AppWebExtensionBackgroundPortDelegate(private val port: WebExtension.Port) : WebExtension.PortDelegate {
    init {
        //main frames are sent only while filters may block pages
        val blocksPages = WebEngineFactory.adBlocker?.blocksPages ?: false
        port.postMessage(JSONObject().put("action", "config").put("checkPages", blocksPages))
    }

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
        } catch (e: Exception) {
            Log.e(TAG, "onBeforeRequest", e)
        } finally {
            val data = JSONObject().put("requestId", requestId).put("block", block)
            port.postMessage(JSONObject().put("action", "onResolveRequest").put("data", data))
        }
    }

    private fun isAd(details: JSONObject): Boolean {
        val blocker = WebEngineFactory.adBlocker ?: return false
        val url = details.optString("url").ifEmpty { return false }
        val type = details.optString("type")
        //a request the user started (the address bar) has no origin
        val document = details.optString("documentUrl").ifEmpty { details.optString("originUrl") }.ifEmpty { null }
        val top = details.optString("topUrl").ifEmpty { null } ?: document
        return blocker.shouldBlock(Uri.parse(url), type, null, type == "main_frame", document?.let(Uri::parse),
            top?.let(Uri::parse))
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
    }

    companion object {
        val TAG: String = AppWebExtensionBackgroundPortDelegate::class.java.simpleName
    }
}
