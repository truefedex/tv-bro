package com.phlox.tvwebbrowser.model.dao

import androidx.room.*
import com.phlox.tvwebbrowser.model.TabEntity

@Dao
interface TabsDao {
    @Query("SELECT * FROM tabs WHERE incognito=:incognito ORDER BY position ASC")
    suspend fun getAll(incognito: Boolean = false): List<TabEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TabEntity): Long

    @Update
    suspend fun update(item: TabEntity)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tabs WHERE incognito = :incognito")
    suspend fun deleteAll(incognito: Boolean = false)

    @Query("UPDATE tabs SET selected = 0 WHERE incognito = :incognito")
    suspend fun unselectAll(incognito: Boolean = false)

    @Query("UPDATE tabs SET position = :position WHERE id = :id")
    suspend fun updatePosition(position: Int, id: Long)

    /** @param positions positions by tab ids */
    @Transaction
    suspend fun updatePositions(positions: Map<Long, Int>) {
        positions.forEach { (id, position) -> updatePosition(position, id) }
    }
}
