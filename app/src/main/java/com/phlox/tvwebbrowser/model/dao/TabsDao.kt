package com.phlox.tvwebbrowser.model.dao

import androidx.room.*
import com.phlox.tvwebbrowser.model.WebTabState

@Dao
interface TabsDao {
    @Query("SELECT * FROM tabs WHERE incognito=:incognito ORDER BY position ASC")
    suspend fun getAll(incognito: Boolean = false): List<WebTabState>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WebTabState): Long

    @Update
    suspend fun update(item: WebTabState)

    @Delete
    suspend fun delete(item: WebTabState)

    @Query("DELETE FROM tabs WHERE incognito = :incognito")
    suspend fun deleteAll(incognito: Boolean = false)

    @Query("UPDATE tabs SET selected = 0 WHERE incognito = :incognito")
    suspend fun unselectAll(incognito: Boolean = false)

    @Query("UPDATE tabs SET position = :position WHERE id = :id")
    suspend fun updatePosition(position: Int, id: Long)

    @Transaction
    suspend fun updatePositions(tabs: List<WebTabState>) {
        tabs.forEach { updatePosition(it.position, it.id) }
    }
}