package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.activity.main.HistoryNavigator
import com.phlox.tvwebbrowser.browser.VisitedHistoryLogger
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.dao.HistoryDao
import java.util.Calendar

/**
 * The browsing history.
 */
class HistoryRepository(private val dao: HistoryDao) : VisitedHistoryLogger.Store, HistoryNavigator.Store {
    companion object {
        private const val MAX_ITEMS = 5000
        private const val KEPT_MONTHS_WHEN_TRIMMED = 3
    }

    /**
     * Deletes the items older than a few months when there are too many of them.
     */
    suspend fun trim() {
        if (dao.count() <= MAX_ITEMS) return
        val c = Calendar.getInstance()
        c.add(Calendar.MONTH, -KEPT_MONTHS_WHEN_TRIMMED)
        dao.deleteWhereTimeLessThan(c.time.time)
    }

    suspend fun last(limit: Int): List<HistoryItem> = dao.last(limit)

    suspend fun mostVisited(): List<HistoryItem> = dao.frequentlyUsedUrls()

    /**
     * The whole history, the latest items first. [trim] keeps it small enough to be searched in memory
     * (SQLite's LIKE folds the case of ASCII letters only).
     */
    override suspend fun all(): List<HistoryItem> = dao.allByTime()

    override suspend fun delete(items: List<HistoryItem>) = dao.delete(*items.toTypedArray())

    override suspend fun deleteAll() = dao.deleteWhereTimeLessThan(Long.MAX_VALUE)

    override suspend fun insert(item: HistoryItem): Long = dao.insert(item)

    override suspend fun updateTitle(id: Long, title: String) = dao.updateTitle(id, title)
}
