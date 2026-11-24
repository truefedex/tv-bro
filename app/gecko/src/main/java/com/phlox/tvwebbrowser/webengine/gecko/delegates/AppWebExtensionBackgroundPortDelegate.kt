package com.phlox.tvwebbrowser.webengine.gecko.delegates

import com.phlox.tvwebbrowser.webengine.gecko.GeckoSiteBlocks
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
        current = this
        sendConfig()
    }

    /** Main frames are sent only while filters may block pages: ad filters of pages, or the site filter */
    fun sendConfig() {
        val checkPages = WebEngineFactory.adBlocker?.blocksPages == true || WebEngineFactory.siteFilter?.active == true
        port.postMessage(JSONObject().put("action", "config").put("checkPages", checkPages))
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
            block = isBlockedSite(details) || isAd(details)
        } catch (e: Exception) {
            Log.e(TAG, "onBeforeRequest", e)
        } finally {
            val data = JSONObject().put("requestId", requestId).put("block", block)
            port.postMessage(JSONObject().put("action", "onResolveRequest").put("data", data))
        }
    }

    /**
     * The site filter first: its requests fail, its pages too (`onLoadRequest` shows the block page of those it sees,
     * [GeckoSiteBlocks])
     */
    private fun isBlockedSite(details: JSONObject): Boolean {
        val filter = WebEngineFactory.siteFilter ?: return false
        if (!filter.active) return false
        val url = details.optString("url").ifEmpty { return false }
        val mainFrame = details.optString("type") == "main_frame"
        val top = details.optString("topUrl").ifEmpty { null }
        val block = filter.check(Uri.parse(url), mainFrame, top?.let(Uri::parse)) ?: return false
        if (mainFrame) GeckoSiteBlocks.cancelled(url, block)
        return true
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

        /** The port of the background script, once it connected (one for the runtime) */
        @Volatile
        var current: AppWebExtensionBackgroundPortDelegate? = null
            private set
    }
}
