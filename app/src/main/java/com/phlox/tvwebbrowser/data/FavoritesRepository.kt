package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.dao.FavoritesDao

/**
 * Bookmarks: the user's ones and the ones of the built-in home page.
 */
class FavoritesRepository(private val dao: FavoritesDao) {
    suspend fun all(): List<FavoriteItem> = dao.getAll()

    suspend fun homePageBookmarks(): List<FavoriteItem> = dao.getHomePageBookmarks()

    suspend fun get(id: Long): FavoriteItem? = dao.getById(id)

    /**
     * Inserts (id 0, the id is set to the item) or updates the bookmark.
     */
    suspend fun save(item: FavoriteItem) {
        if (item.id == 0L) {
            item.id = dao.insert(item)
        } else {
            dao.update(item)
        }
    }

    suspend fun delete(item: FavoriteItem) = dao.delete(item)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun markAsUseful(id: Long) = dao.markAsUseful(id)
}
