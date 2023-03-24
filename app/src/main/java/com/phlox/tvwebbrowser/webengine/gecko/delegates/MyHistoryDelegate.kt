package com.phlox.tvwebbrowser.webengine.gecko.delegates

import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

class MyHistoryDelegate(private val geckoWebEngine: GeckoWebEngine) : GeckoSession.HistoryDelegate {
    override fun onVisited(
        session: GeckoSession,
        url: String,
        lastVisitedURL: String?,
        flags: Int
    ): GeckoResult<Boolean>? {
        return super.onVisited(session, url, lastVisitedURL, flags)
    }

    override fun getVisited(
        session: GeckoSession,
        urls: Array<out String>
    ): GeckoResult<BooleanArray>? {
        return super.getVisited(session, urls)
    }

    override fun onHistoryStateChange(
        session: GeckoSession,
        historyList: GeckoSession.HistoryDelegate.HistoryList
    ) {
        super.onHistoryStateChange(session, historyList)
    }
}