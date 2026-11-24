package com.phlox.tvwebbrowser.webengine.gecko.delegates

import com.phlox.tvwebbrowser.webengine.gecko.GeckoSiteBlocks
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ProgressDelegate

class MyProgressDelegate(private val geckoWebEngine: GeckoWebEngine): ProgressDelegate {
    var sessionState: GeckoSession.SessionState? = null

    /** The page that started last, as GeckoView names it */
    private var startedUrl: String? = null

    override fun onPageStart(session: GeckoSession, url: String) {
        startedUrl = url
        geckoWebEngine.onPageStartedForFrames()
        geckoWebEngine.callback?.onPageStarted(GeckoWebEngine.toAppUrl(url))
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        //a page the site filter blocked in a load onLoadRequest does not see (Back, a restored tab): the background
        //script cancelled it, which leaves it blank
        GeckoSiteBlocks.takeCancelled(startedUrl)?.let { block ->
            geckoWebEngine.showSiteBlockPage(startedUrl!!, block, replace = true)
        }
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