package com.phlox.tvwebbrowser.model.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomWarnings
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

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    @Query("SELECT * FROM history ORDER BY time DESC LIMIT :limit")
    suspend fun last(limit: Int = 1): List<HistoryItem>

    @SuppressWarnings(RoomWarnings.CURSOR_MISMATCH)
    @Query("SELECT \"\" as id, title, url, count(url) as cnt , max(time) as time FROM history GROUP BY title, url ORDER BY cnt DESC, time DESC LIMIT 8")
    suspend fun frequentlyUsedUrls(): List<HistoryItem>

    @Query("SELECT * FROM history ORDER BY time DESC")
    suspend fun allByTime(): List<HistoryItem>

    @Query("UPDATE history SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)
}