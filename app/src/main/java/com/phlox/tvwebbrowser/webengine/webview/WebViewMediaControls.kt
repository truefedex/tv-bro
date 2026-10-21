package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.PageVideoList
import com.phlox.tvwebbrowser.webengine.PageVideoReports
import org.json.JSONException
import org.json.JSONObject

enum class MediaCommand(val js: String) {
    TOGGLE("toggle"), STOP("stop"), REWIND("rewind"), FAST_FORWARD("forward")
}

/**
 * The media keys of the remote. `media_controls.js` runs in every frame (players of other sites are mostly in
 * iframes) and reports the media of the frame that its commands go to, to [CHANNEL]; a command goes to the frame
 * [MediaFrames] chooses, through the reply proxy of that frame, so pages can not send commands to the players of
 * other frames. WebViews without these features get the script in the main frame only, after the page loads,
 * and report no state.
 */
internal class WebViewMediaControls(private val webView: WebView, private val onStateChanged: (MediaState?) -> Unit,
                                    private val onVideosChanged: (List<PageVideo>) -> Unit) {
    private val frames = MediaFrames<JavaScriptReplyProxy>()
    /** The videos other apps can play, also reported by the script of each frame (page_videos.js) */
    private val videos = PageVideoList<JavaScriptReplyProxy>()
    private val allFrames = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private var state: MediaState? = null

    init {
        if (allFrames) {
            //the object is there before the script of a new document runs
            WebViewCompat.addWebMessageListener(webView, CHANNEL, setOf("*")) { _, message, _, _, replyProxy ->
                onState(message.data, replyProxy)
            }
            WebViewCompat.addDocumentStartJavaScript(webView, script(webView.context), setOf("*"))
        }
    }

    private fun onState(data: String?, frame: JavaScriptReplyProxy) {
        val json = try {
            JSONObject(data ?: return)
        } catch (e: JSONException) {
            Log.w(TAG, "Bad media state: $data")
            return
        }
        if (json.has("videos")) {
            onVideosChanged(videos.onFrameVideos(frame, PageVideoReports.parse(json)))
            return
        }
        frames.onState(frame, parse(json, SystemClock.elapsedRealtime()))
        updateState()
    }

    private fun updateState() {
        val target = frames.targetMedia()
        if (target == state) return
        state = target
        onStateChanged(target)
    }

    fun command(command: MediaCommand) = send(command.js)

    fun seekTo(position: Double) = send("seekTo:$position")

    fun seekBy(delta: Double) = send("seekBy:$delta")

    private fun send(command: String) {
        val frame = frames.target()
        if (frame != null) {
            frame.postMessage(command)
        } else {
            webView.evaluateJavascript("window.tvBroMediaCommand && tvBroMediaCommand('$command')", null)
        }
    }

    /** The main frame starts another page */
    fun onPageStarted() {
        frames.clear()
        updateState()
        videos.clear()
        onVideosChanged(emptyList())
    }

    fun onPageFinished() {
        if (!allFrames) webView.evaluateJavascript(script(webView.context), null)
    }

    companion object {
        private const val TAG = "WebViewMediaControls"
        /** The object the script finds in every frame */
        private const val CHANNEL = "tvBroMedia"
        private var script: String? = null

        /** The videos of the frame first: media_controls.js starts them */
        private fun script(context: Context): String = script
            ?: listOf("page_videos.js", "media_controls.js").joinToString("\n") { name ->
                context.assets.open(name).bufferedReader().use { it.readText() }
            }.also { script = it }

        /** A report of the script, received at [now] (elapsedRealtime); null: the frame has no media */
        internal fun parse(json: JSONObject, now: Long): MediaState? {
            if (!json.optBoolean("media")) return null
            val duration = json.optDouble("duration")
            return MediaState(
                playing = json.optBoolean("playing"),
                position = json.optDouble("position", 0.0).takeIf { it.isFinite() } ?: 0.0,
                duration = duration.takeIf { it.isFinite() && it > 0 },
                fullscreen = json.optBoolean("fullscreen"),
                rate = json.optDouble("rate", 1.0).takeIf { it.isFinite() } ?: 1.0,
                at = now,
            )
        }
    }
}
