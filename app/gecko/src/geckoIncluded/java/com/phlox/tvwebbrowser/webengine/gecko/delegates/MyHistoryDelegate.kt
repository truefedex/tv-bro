package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.util.Log
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

class MyHistoryDelegate(private val webEngine: GeckoWebEngine) : GeckoSession.HistoryDelegate {
    companion object {
        val TAG: String = MyHistoryDelegate::class.java.simpleName
    }

    private val visitedURLs = HashSet<String>()

    override fun onVisited( session: GeckoSession, url: String, lastVisitedURL: String?, flags: Int): GeckoResult<Boolean> {
        Log.i(TAG,"Visited URL: $url")
        visitedURLs.add(url)
        if (flags and GeckoSession.HistoryDelegate.VISIT_TOP_LEVEL != 0) {
            webEngine.callback?.onVisited(url)
        }
        return GeckoResult.fromValue(true)
    }

    override fun getVisited(session: GeckoSession, urls: Array<out String>): GeckoResult<BooleanArray> {
        val visited = BooleanArray(urls.size)
        for (i in urls.indices) {
            visited[i] = visitedURLs.contains(urls[i])
        }
        return GeckoResult.fromValue(visited)
    }

    override fun onHistoryStateChange(session: GeckoSession, historyList: GeckoSession.HistoryDelegate.HistoryList) {
        Log.i(TAG, "History state updated")
    }
}