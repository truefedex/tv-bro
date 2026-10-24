package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.os.SystemClock
import android.util.Log
import com.phlox.tvwebbrowser.webengine.MediaReports
import com.phlox.tvwebbrowser.webengine.PageVideoReports
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

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
            //the background script blocked a request of this tab
            json.has("blockedUrl") -> webEngine.callback?.onBlockedAd(json.getString("blockedUrl"))
            json.has("videos") -> webEngine.onFrameVideos(port, PageVideoReports.parse(json))
            MediaReports.isReport(json) -> webEngine.onFrameMedia(port, MediaReports.parse(json, SystemClock.elapsedRealtime()),
                MediaReports.source(json))
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
        //the frame is gone
        webEngine.onFrameVideos(port, emptyList())
        webEngine.onFrameMedia(port, null, null)
        if (webEngine.appContentScriptPortDelegate == this) webEngine.appContentScriptPortDelegate = null
    }

    companion object {
        val TAG: String = AppContentScriptPortDelegate::class.java.simpleName
    }
}
