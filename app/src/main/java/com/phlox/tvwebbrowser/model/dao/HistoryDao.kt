package com.phlox.tvwebbrowser.model.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.phlox.tvwebbrowser.model.HistoryItem

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history")
    suspend fun getAll(): List<HistoryItem>

    @Insert
    suspend fun insert(item: HistoryItem): Long

    @Delete
    suspend fun delete(vararg item: HistoryItem)

    @Query("DELETE FROM history WHERE time < :time")
    suspend fun deleteWhereTimeLessThan(time: Long)

    /**
     * Removes any existing entry for [url] so a new visit replaces it instead of stacking a second
     * row (history uniqueness: the same site appears once, with the most recent timestamp).
     */
    @Query("DELETE FROM history WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    @Query("SELECT * FROM history ORDER BY time DESC LIMIT :limit")
    suspend fun last(limit: Int = 1): List<HistoryItem>

    @Query("SELECT * FROM history ORDER BY time DESC LIMIT 100 OFFSET :offset")
    suspend fun allByLimitOffset(offset: Long): List<HistoryItem>

    @Query("SELECT * FROM history WHERE (title LIKE :titleQuery) OR (url LIKE :urlQuery) ORDER BY time DESC LIMIT 100")
    suspend fun search(titleQuery: String, urlQuery: String): List<HistoryItem>

    @Query("UPDATE history SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)
}