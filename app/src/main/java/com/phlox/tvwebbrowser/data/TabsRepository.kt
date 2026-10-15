package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.model.TabEntity
import com.phlox.tvwebbrowser.model.dao.TabsDao

/**
 * Stored tabs (snapshots of [com.phlox.tvwebbrowser.model.BrowserTab]).
 */
class TabsRepository(private val dao: TabsDao) {
    suspend fun load(incognito: Boolean): List<TabEntity> = dao.getAll(incognito)

    /**
     * Inserts (id 0) or updates the tab; a selected tab unselects the others.
     * @return the id of the tab
     */
    suspend fun save(tab: TabEntity): Long = dao.save(tab)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun deleteAll(incognito: Boolean) = dao.deleteAll(incognito)

    /** @param positions positions by tab ids */
    suspend fun updatePositions(positions: Map<Long, Int>) = dao.updatePositions(positions)
}
