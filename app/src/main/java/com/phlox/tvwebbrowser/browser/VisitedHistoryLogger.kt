package com.phlox.tvwebbrowser.browser

import android.util.Log
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.HistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Writes visited pages to the history. A visit is saved only after [MIN_VISITED_INTERVAL]:
 * if another page is visited before that (redirects, quick navigation), the previous visit is dropped.
 *
 * A visit gets only a title received for its own url: engines report the visit when the page commits,
 * before its title, and pages that change the url themselves (history.pushState) do not start a new load.
 */
class VisitedHistoryLogger(
    private val scope: CoroutineScope,
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis
) {
    interface Store {
        suspend fun insert(item: HistoryItem): Long
        suspend fun updateTitle(id: Long, title: String)
    }

    companion object {
        private const val TAG = "VisitedHistoryLogger"
        const val MIN_VISITED_INTERVAL = 5000L
    }

    var lastHistoryItem: HistoryItem? = null
    private var lastHistoryItemSaveJob: Job? = null
    /** The last title received and the url of the page it came for */
    private var lastTitle: String? = null
    private var lastTitleUrl: String? = null

    /**
     * @param pageTitle the title of the page when the visit is saved, if no title came for its url
     * until then (a page that keeps the title of the previous one); null if it is not known
     */
    fun logVisited(url: String, pageTitle: () -> String? = { null }) {
        Log.d(TAG, "logVisited: $url")
        if ((url == lastHistoryItem?.url) || url == Config.HOME_PAGE_URL || !url.startsWith("http", true)) {
            return
        }

        val now = clock()

        lastHistoryItem?.let {
            if ((!it.saved) && (it.time + MIN_VISITED_INTERVAL) > now) {
                lastHistoryItemSaveJob?.cancel()
            }
        }

        val item = HistoryItem()
        item.url = url
        item.title = if (lastTitleUrl == url) lastTitle ?: "" else ""
        item.time = now
        lastHistoryItem = item
        lastHistoryItemSaveJob = scope.launch {
            delay(MIN_VISITED_INTERVAL)
            if (item.title.isEmpty()) item.title = pageTitle() ?: ""
            item.id = store.insert(item)
            item.saved = true
        }
    }

    /** A page got its [title]; [url] is the address the engine shows now */
    fun onTitleUpdated(url: String, title: String) {
        lastTitle = title
        lastTitleUrl = url
        val lastHistoryItem = lastHistoryItem ?: return
        if (url == lastHistoryItem.url) {
            lastHistoryItem.title = title
            if (lastHistoryItem.saved) {
                scope.launch {
                    store.updateTitle(lastHistoryItem.id, lastHistoryItem.title)
                }
            }
        }
    }
}
