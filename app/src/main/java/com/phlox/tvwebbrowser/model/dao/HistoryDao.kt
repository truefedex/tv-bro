package com.phlox.tvwebbrowser.model.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.phlox.tvwebbrowser.model.HistoryItem


@Dao
interface HistoryDao {
    @Query("SELECT * FROM history")
    fun getAll(): List<HistoryItem>

    @Insert
    fun insert(vararg item: HistoryItem)

    @Delete
    suspend fun delete(vararg item: HistoryItem)

    @Query("DELETE FROM history WHERE time < :time")
    fun deleteWhereTimeLessThan(time: Long)

    @Query("SELECT COUNT(*) FROM history")
    fun count(): Int

    @Query("SELECT * FROM history ORDER BY time DESC LIMIT 1")
    fun last(): List<HistoryItem>

    @SuppressWarnings(RoomWarnings.CURSOR_MISMATCH)
    @Query("SELECT \"\" as id, title, url, favicon, count(url) as cnt , max(time) as time FROM history GROUP BY title, url, favicon ORDER BY cnt DESC, time DESC LIMIT 8")
    fun frequentlyUsedUrls(): List<HistoryItem>

    @Query("SELECT * FROM history ORDER BY time DESC LIMIT 100 OFFSET :offset")
    suspend fun allByLimitOffset(offset: Long): List<HistoryItem>

    @Query("SELECT * FROM history WHERE (title LIKE :titleQuery) OR (url LIKE :urlQuery) ORDER BY time DESC LIMIT 100")
    suspend fun search(titleQuery: String, urlQuery: String): List<HistoryItem>
}