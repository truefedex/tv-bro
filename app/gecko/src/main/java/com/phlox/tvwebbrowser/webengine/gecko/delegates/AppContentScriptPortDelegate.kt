package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.util.Log
import com.phlox.tvwebbrowser.webengine.PageVideoReports
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * The port of the content script of one frame (each frame connects its own): it reports the videos
 * of the frame (page_videos.js) as JSON strings.
 */
class AppContentScriptPortDelegate(val port: WebExtension.Port, val webEngine: GeckoWebEngine): WebExtension.PortDelegate {

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        val json = try {
            JSONObject(message as? String ?: return)
        } catch (e: JSONException) {
            Log.w(TAG, "Bad message: $message")
            return
        }
        if (json.has("videos")) webEngine.onFrameVideos(port, PageVideoReports.parse(json))
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
        //the frame is gone
        webEngine.onFrameVideos(port, emptyList())
        if (webEngine.appContentScriptPortDelegate == this) webEngine.appContentScriptPortDelegate = null
    }

    companion object {
        val TAG: String = AppContentScriptPortDelegate::class.java.simpleName
    }
}
