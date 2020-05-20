package com.phlox.tvwebbrowser.model.dao

import androidx.room.*
import com.phlox.tvwebbrowser.model.FavoriteItem

@Dao
interface FavoritesDao {
    @Query("SELECT * FROM favorites WHERE parent=0 ORDER BY id DESC")
    suspend fun getAll(): List<FavoriteItem>

    @Insert
    suspend fun insert(item: FavoriteItem): Long

    @Update
    suspend fun update(item: FavoriteItem)

    @Delete
    suspend fun delete(item: FavoriteItem)
}