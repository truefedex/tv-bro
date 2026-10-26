package com.phlox.tvwebbrowser.model.dao

import androidx.room.*
import com.phlox.tvwebbrowser.model.FavoriteItem
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoritesDao {
    @Query("SELECT * FROM favorites WHERE parent=0 AND home_page_bookmark=:homePageBookmarks ORDER BY id DESC")
    suspend fun getAll(homePageBookmarks: Boolean = false): List<FavoriteItem>

    @Query("SELECT * FROM favorites WHERE parent=0 AND home_page_bookmark=1 ORDER BY i_order ASC, id ASC")
    suspend fun getHomePageBookmarks(): List<FavoriteItem>

    /** Folders (no url) first, then bookmarks; each by their order, the newest first among equal orders */
    @Query("SELECT * FROM favorites WHERE parent=:parent AND home_page_bookmark=0 ORDER BY url IS NOT NULL, i_order ASC, id DESC")
    suspend fun getChildren(parent: Long): List<FavoriteItem>

    /** The bookmarks of every folder, Home page included */
    @Query("SELECT * FROM favorites WHERE url IS NOT NULL")
    suspend fun getBookmarks(): List<FavoriteItem>

    @Query("SELECT * FROM favorites WHERE url IS NOT NULL")
    fun observeBookmarks(): Flow<List<FavoriteItem>>

    @Query("SELECT * FROM favorites WHERE url IS NULL AND home_page_bookmark=0")
    suspend fun getFolders(): List<FavoriteItem>

    @Query("SELECT COUNT(*) FROM favorites WHERE parent=:parent AND home_page_bookmark=0 AND url IS NOT NULL")
    suspend fun countBookmarks(parent: Long): Int

    @Query("SELECT * FROM favorites WHERE url=:url ORDER BY home_page_bookmark ASC, id DESC LIMIT 1")
    suspend fun findByUrl(url: String): FavoriteItem?

    @Query("SELECT MIN(i_order) FROM favorites WHERE parent=:parent AND home_page_bookmark=0")
    suspend fun minOrder(parent: Long): Int?

    @Query("SELECT MAX(i_order) FROM favorites WHERE parent=0 AND home_page_bookmark=1")
    suspend fun maxHomePageOrder(): Int?

    @Insert
    suspend fun insert(item: FavoriteItem): Long

    @Update
    suspend fun update(item: FavoriteItem)

    @Update
    suspend fun update(items: List<FavoriteItem>)

    @Delete
    suspend fun delete(item: FavoriteItem)

    @Query("DELETE FROM favorites WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM favorites WHERE id=:id")
    suspend fun getById(id: Long): FavoriteItem?

    @Query("UPDATE favorites SET useful=1 WHERE id=:favoriteId")
    suspend fun markAsUseful(favoriteId: Long)
}