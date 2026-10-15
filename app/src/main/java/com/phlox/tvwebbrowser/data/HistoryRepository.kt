package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.browser.VisitedHistoryLogger
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.dao.HistoryDao
import java.util.Calendar

/**
 * The browsing history.
 */
class HistoryRepository(private val dao: HistoryDao) : VisitedHistoryLogger.Store {
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

    /** A page of the history, the latest items first */
    suspend fun page(offset: Long): List<HistoryItem> = dao.allByLimitOffset(offset)

    /** Items with the query in the title or the url */
    suspend fun search(query: String): List<HistoryItem> = dao.search(query, query)

    suspend fun delete(items: List<HistoryItem>) = dao.delete(*items.toTypedArray())

    suspend fun deleteAll() = dao.deleteWhereTimeLessThan(Long.MAX_VALUE)

    override suspend fun insert(item: HistoryItem): Long = dao.insert(item)

    override suspend fun updateTitle(id: Long, title: String) = dao.updateTitle(id, title)
}
