package com.phlox.tvwebbrowser.model.dao

import androidx.room.*
import com.phlox.tvwebbrowser.model.Download

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads")
    fun getAll(): List<Download>

    @Insert
    fun insert(download: Download): Long

    @Update
    fun update(vararg downloads: Download)

    @Delete
    fun delete(download: Download)

    @Query("SELECT * FROM downloads ORDER BY time DESC LIMIT 100 OFFSET :offset")
    suspend fun allByLimitOffset(offset: Long): List<Download>
}