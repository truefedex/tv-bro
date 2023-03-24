package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.view.PointerIcon
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.SlowScriptResponse
import org.mozilla.geckoview.WebResponse

class MyContentDelegate(private val geckoWebEngine: GeckoWebEngine): GeckoSession.ContentDelegate {
    override fun onTitleChange(session: GeckoSession, title: String?) {
        title?.let { geckoWebEngine.callback?.onReceivedTitle(it) }
    }

    override fun onPreviewImage(session: GeckoSession, previewImageUrl: String) {
        super.onPreviewImage(session, previewImageUrl)
    }

    override fun onFocusRequest(session: GeckoSession) {
        super.onFocusRequest(session)
    }

    override fun onCloseRequest(session: GeckoSession) {
        super.onCloseRequest(session)
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        super.onFullScreen(session, fullScreen)
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
        super.onContextMenu(session, screenX, screenY, element)
    }

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        super.onExternalResponse(session, response)
    }

    override fun onCrash(session: GeckoSession) {
        super.onCrash(session)
    }

    override fun onKill(session: GeckoSession) {
        super.onKill(session)
    }

    override fun onFirstComposite(session: GeckoSession) {
        super.onFirstComposite(session)
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
        super.onWebAppManifest(session, manifest)
    }

    override fun onSlowScript(
        geckoSession: GeckoSession,
        scriptFileName: String
    ): GeckoResult<SlowScriptResponse>? {
        return super.onSlowScript(geckoSession, scriptFileName)
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