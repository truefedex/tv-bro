package com.phlox.tvwebbrowser.webengine.gecko.delegates

import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ProgressDelegate

class MyProgressDelegate(private val geckoWebEngine: GeckoWebEngine): ProgressDelegate {
    var sessionState: GeckoSession.SessionState? = null

    override fun onPageStart(session: GeckoSession, url: String) {
        geckoWebEngine.callback?.onPageStarted(url)
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        geckoWebEngine.callback?.onPageFinished(geckoWebEngine.url)
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        geckoWebEngine.callback?.onProgressChanged(progress)
    }

    override fun onSecurityChange(
        session: GeckoSession,
        securityInfo: ProgressDelegate.SecurityInformation
    ) {
        super.onSecurityChange(session, securityInfo)
    }

    override fun onSessionStateChange(
        session: GeckoSession,
        sessionState: GeckoSession.SessionState
    ) {
        this.sessionState = sessionState
    }
}