package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject

enum class MediaCommand(val js: String) {
    TOGGLE("toggle"), STOP("stop"), REWIND("rewind"), FAST_FORWARD("forward")
}

/**
 * The media keys of the remote. `media_controls.js` runs in every frame (players of other sites are mostly in
 * iframes) and reports the state of the frame's media to [CHANNEL]; a command goes to the frame [MediaFrames]
 * chooses, through the reply proxy of that frame, so pages can not send commands to the players of other frames.
 * WebViews without these features get the script in the main frame only, after the page loads.
 */
internal class WebViewMediaControls(private val webView: WebView) {
    private val frames = MediaFrames<JavaScriptReplyProxy>()
    private val allFrames = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

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
        val state = try {
            JSONObject(data ?: return)
        } catch (e: JSONException) {
            Log.w(TAG, "Bad media state: $data")
            return
        }
        frames.onState(frame, state.optBoolean("media"), state.optBoolean("playing"), state.optBoolean("fullscreen"))
    }

    fun command(command: MediaCommand) {
        val frame = frames.target()
        if (frame != null) {
            frame.postMessage(command.js)
        } else {
            webView.evaluateJavascript("window.tvBroMediaCommand && tvBroMediaCommand('${command.js}')", null)
        }
    }

    /** The main frame starts another page */
    fun onPageStarted() = frames.clear()

    fun onPageFinished() {
        if (!allFrames) webView.evaluateJavascript(script(webView.context), null)
    }

    companion object {
        private const val TAG = "WebViewMediaControls"
        /** The object the script finds in every frame */
        private const val CHANNEL = "tvBroMedia"
        private var script: String? = null

        private fun script(context: Context): String = script
            ?: context.assets.open("media_controls.js").bufferedReader().use { it.readText() }.also { script = it }
    }
}
