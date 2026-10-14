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

    fun logVisited(title: String?, url: String, faviconHash: String?) {
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
        item.title = title ?: ""
        item.time = now
        item.favicon = faviconHash
        lastHistoryItem = item
        lastHistoryItemSaveJob = scope.launch {
            delay(MIN_VISITED_INTERVAL)
            item.id = store.insert(item)
            item.saved = true
        }
    }

    fun onTitleUpdated(url: String, title: String) {
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
