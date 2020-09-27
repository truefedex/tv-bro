package com.phlox.tvwebbrowser.model.dao

import androidx.room.*
import com.phlox.tvwebbrowser.model.WebTabState

@Dao
interface TabsDao {
    @Query("SELECT * FROM tabs WHERE incognito=:incognito ORDER BY position ASC")
    suspend fun getAll(incognito: Boolean = false): List<WebTabState>

    @Insert
    suspend fun insert(item: WebTabState): Long

    @Update
    suspend fun update(item: WebTabState)

    @Delete
    suspend fun delete(item: WebTabState)

    @Query("DELETE FROM tabs WHERE incognito = :incognito")
    suspend fun deleteAll(incognito: Boolean = false)
}