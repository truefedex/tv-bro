package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.webengine.MediaFrames
import com.phlox.tvwebbrowser.webengine.MediaReports
import com.phlox.tvwebbrowser.webengine.PageVideoMatch
import com.phlox.tvwebbrowser.webengine.MediaSource
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.PageVideoList
import com.phlox.tvwebbrowser.webengine.PageVideoReports
import com.phlox.tvwebbrowser.webengine.MediaCommand
import org.json.JSONException
import org.json.JSONObject

/**
 * The media keys of the remote. `media_controls.js` runs in every frame (players of other sites are mostly in
 * iframes, `webview_media.js` starts it) and reports the media of the frame that its commands go to, to
 * [CHANNEL]; a command goes to the frame [MediaFrames] chooses, through the reply proxy of that frame, so pages
 * can not send commands to the players of other frames. WebViews without these features get the script in the
 * main frame only, after the page loads, and report no state.
 */
internal class WebViewMediaControls(private val webView: WebView, private val onStateChanged: (MediaState?) -> Unit,
                                    private val onVideosChanged: (List<PageVideo>) -> Unit,
                                    private val onMediaVideoChanged: (PageVideo?) -> Unit) {
    private val frames = MediaFrames<JavaScriptReplyProxy>()
    /** What the media of each frame plays, for [mediaVideo] */
    private val sources = HashMap<JavaScriptReplyProxy, MediaSource>()
    private var mediaVideo: PageVideo? = null
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
            updateMediaVideo()
            return
        }
        frames.onState(frame, MediaReports.parse(json, SystemClock.elapsedRealtime()))
        val source = MediaReports.source(json)
        if (source == null) sources.remove(frame) else sources[frame] = source
        updateState()
        updateMediaVideo()
    }

    /** The video of the page the media the keys control plays ([PageVideoMatch]) */
    private fun updateMediaVideo() {
        val frame = frames.target()
        val video = frame?.let { PageVideoMatch.of(sources[it], videos.videosOf(it)) }
        if (video == mediaVideo) return
        mediaVideo = video
        onMediaVideoChanged(video)
    }

    private fun updateState() {
        val target = frames.targetMedia()
        if (target == state) return
        state = target
        onStateChanged(target)
    }

    fun command(command: MediaCommand) = send(command.js)

    fun seekTo(position: Double) = send(MediaCommand.seekTo(position))

    fun seekBy(delta: Double) = send(MediaCommand.seekBy(delta))

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
        sources.clear()
        updateState()
        videos.clear()
        onVideosChanged(emptyList())
        updateMediaVideo()
    }

    fun onPageFinished() {
        if (!allFrames) webView.evaluateJavascript(script(webView.context), null)
    }

    companion object {
        private const val TAG = "WebViewMediaControls"
        /** The object the script finds in every frame */
        private const val CHANNEL = "tvBroMedia"
        private var script: String? = null

        /** The shared scripts first: webview_media.js starts them */
        private fun script(context: Context): String = script
            ?: listOf("page_videos.js", "media_controls.js", "webview_media.js").joinToString("\n") { name ->
                context.assets.open(name).bufferedReader().use { it.readText() }
            }.also { script = it }
    }
}
