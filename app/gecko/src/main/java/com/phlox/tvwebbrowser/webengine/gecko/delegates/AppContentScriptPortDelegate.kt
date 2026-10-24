package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.phlox.tvwebbrowser.webengine.MediaReports
import com.phlox.tvwebbrowser.webengine.PageVideoReports
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.Executors

/**
 * The port of the content script of one frame (each frame connects its own): it reports the media of the
 * frame (media_controls.js) and its videos (page_videos.js) as JSON strings, and gets the media commands.
 */
class AppContentScriptPortDelegate(val port: WebExtension.Port, val webEngine: GeckoWebEngine): WebExtension.PortDelegate {

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        val json = try {
            JSONObject(message as? String ?: return)
        } catch (e: JSONException) {
            Log.w(TAG, "Bad message: $message")
            return
        }
        when {
            json.has("hidingStyles") || json.has("genericHiding") -> answerHiding(json, port)
            //the background script blocked a request of this tab
            json.has("blockedUrl") -> webEngine.callback?.onBlockedAd(json.getString("blockedUrl"))
            json.has("videos") -> webEngine.onFrameVideos(port, PageVideoReports.parse(json))
            MediaReports.isReport(json) -> webEngine.onFrameMedia(port, MediaReports.parse(json, SystemClock.elapsedRealtime()),
                MediaReports.source(json))
        }
    }

    /**
     * The CSS of the element hiding of the frame, made on a thread of its own (lists of classes can be long on
     * weak TVs) and sent back on the main thread, with the page of the tab for its site setting
     */
    private fun answerHiding(json: JSONObject, port: WebExtension.Port) {
        val blocker = WebEngineFactory.adBlocker ?: return
        val request = json.optInt("hidingRequest")
        //the top frame is the page itself (the url of the engine may still be the previous page)
        val engineTop = if (json.optBoolean("top")) null else webEngine.url?.let(Uri::parse)
        hidingExecutor.execute {
            val reply = JSONObject().put("hidingReply", request)
            try {
                if (json.has("hidingStyles")) {
                    val document = Uri.parse(json.getString("hidingStyles"))
                    val styles = blocker.hidingStyles(document, engineTop ?: document)
                    reply.put("css", styles.css).put("generic", styles.generic)
                } else {
                    val document = Uri.parse(json.getString("genericHiding"))
                    reply.put("css", blocker.genericHidingStyles(document, engineTop ?: document,
                        names(json.optJSONArray("classes")), names(json.optJSONArray("ids"))))
                }
            } catch (e: Exception) {
                Log.e(TAG, "element hiding", e)
                reply.put("css", "").put("generic", false)
            }
            mainHandler.post { port.postMessage(reply) }
        }
    }

    private fun names(array: JSONArray?): List<String> =
        if (array == null) emptyList() else List(array.length()) { array.optString(it) }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
        //the frame is gone
        webEngine.onFrameVideos(port, emptyList())
        webEngine.onFrameMedia(port, null, null)
        if (webEngine.appContentScriptPortDelegate == this) webEngine.appContentScriptPortDelegate = null
    }

    companion object {
        val TAG: String = AppContentScriptPortDelegate::class.java.simpleName
        /** One thread for the element hiding of all frames */
        private val hidingExecutor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
