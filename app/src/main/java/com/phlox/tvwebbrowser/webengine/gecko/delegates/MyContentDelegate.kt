package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.util.Log
import android.view.PointerIcon
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.DownloadUtils
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.gecko.GeckoViewWithVirtualCursor
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.SlowScriptResponse
import org.mozilla.geckoview.WebResponse

class MyContentDelegate(private val webEngine: GeckoWebEngine): GeckoSession.ContentDelegate {
    private var activeAlert: Boolean = false

    companion object {
        val TAG: String = MyContentDelegate::class.java.simpleName
    }

    override fun onTitleChange(session: GeckoSession, title: String?) {
        title?.let { webEngine.callback?.onReceivedTitle(it) }
    }

    override fun onPreviewImage(session: GeckoSession, previewImageUrl: String) {
        super.onPreviewImage(session, previewImageUrl)
    }

    override fun onFocusRequest(session: GeckoSession) {
        Log.i(TAG, "Content requesting focus")
    }

    override fun onCloseRequest(session: GeckoSession) {
        Log.d(TAG, "onCloseRequest")
        webEngine.callback?.closeWindow(session)
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        if (fullScreen) {
            webEngine.callback?.onPrepareForFullscreen()
        } else {
            webEngine.callback?.onExitFullscreen()
        }
    }

    override fun onMetaViewportFitChange(session: GeckoSession, viewportFit: String) {
        super.onMetaViewportFitChange(session, viewportFit)
    }

    override fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement
    ) {
        Log.d(TAG,"onContextMenu screenX="
                    + screenX
                    + " screenY="
                    + screenY
                    + " type="
                    + element.type
                    + " linkUri="
                    + element.linkUri
                    + " title="
                    + element.title
                    + " alt="
                    + element.altText
                    + " srcUri="
                    + element.srcUri
        )
        val linkUri = element.linkUri ?: element.srcUri ?: return
        val webView = webEngine.getView() as? GeckoViewWithVirtualCursor ?: return
        webEngine.callback?.suggestActionsForLink(linkUri, webView.cursorPosition.x.toInt(),
            webView.cursorPosition.y.toInt()
        )
    }

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        Log.d(TAG, "onExternalResponse: " + response.uri)
        val contentDisposition = response.headers.get("content-disposition")
        val mimetype = response.headers.get("content-type")
        val fileName = DownloadUtils.guessFileName(response.uri, contentDisposition, mimetype)
        val contentLength = response.headers.get("content-length")?.toLongOrNull() ?: 0
        webEngine.callback?.onDownloadRequested(
            response.uri,
            webEngine.url ?: "",
            fileName,
            webEngine.userAgentString,
            mimetype,
            Download.OperationAfterDownload.NOP,
            null,
            response.body,
            contentLength
        )
    }

    override fun onCrash(session: GeckoSession) {
        Log.e(TAG, "Crashed, reopening session")
        session.open(GeckoWebEngine.runtime)
    }

    override fun onKill(session: GeckoSession) {
        if (webEngine.session != session || !webEngine.tab.selected) {
            Log.e(TAG, "Background session killed")
            return
        }

        if (Utils.isForeground()) {
            throw IllegalStateException("Foreground content process unexpectedly killed by OS!")
        }

        Log.e(TAG, "Current session killed, reopening")

        webEngine.session.open(GeckoWebEngine.runtime)
        webEngine.url?.let { webEngine.session.loadUri(it) }
    }

    override fun onFirstComposite(session: GeckoSession) {
        Log.d(TAG, "onFirstComposite")
    }

    override fun onFirstContentfulPaint(session: GeckoSession) {
        super.onFirstContentfulPaint(session)
    }

    override fun onPaintStatusReset(session: GeckoSession) {
        super.onPaintStatusReset(session)
    }

    override fun onPointerIconChange(session: GeckoSession, icon: PointerIcon) {
        super.onPointerIconChange(session, icon)
    }

    override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) {
        Log.d(TAG,"onWebAppManifest: $manifest")
    }

    override fun onSlowScript(
        geckoSession: GeckoSession,
        scriptFileName: String
    ): GeckoResult<SlowScriptResponse>? {
        val prompt: MyPromptDelegate? =
            webEngine.session.promptDelegate as? MyPromptDelegate
        val activity = webEngine.callback?.getActivity() ?: return null
        if (prompt != null) {
            val result = GeckoResult<SlowScriptResponse?>()
            if (!activeAlert) {
                activeAlert = true
                prompt.onSlowScriptPrompt(geckoSession, activity.getString(R.string.slow_script), result)
            }
            return result.then<SlowScriptResponse?> { value: SlowScriptResponse? ->
                activeAlert = false
                GeckoResult.fromValue<SlowScriptResponse?>(value)
            }
        }
        return null
    }

    override fun onShowDynamicToolbar(geckoSession: GeckoSession) {
        super.onShowDynamicToolbar(geckoSession)
    }

    override fun onCookieBannerDetected(session: GeckoSession) {
        super.onCookieBannerDetected(session)
    }

    override fun onCookieBannerHandled(session: GeckoSession) {
        super.onCookieBannerHandled(session)
    }
}