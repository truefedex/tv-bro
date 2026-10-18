package com.phlox.tvwebbrowser.model.dao

import androidx.room.*
import com.phlox.tvwebbrowser.model.Download
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY time DESC, id DESC")
    fun observeAll(): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): Download?

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun withStatus(status: Int): List<Download>

    @Insert
    suspend fun insert(download: Download): Long

    @Update
    suspend fun update(download: Download)

    @Query("UPDATE downloads SET bytes_received = :bytesReceived, size = :size WHERE id = :id")
    suspend fun updateProgress(id: Long, bytesReceived: Long, size: Long)

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: Int, error: String?)

    /** The downloads in progress started [before] become paused if they can be resumed, failed otherwise */
    @Query("UPDATE downloads SET status = CASE WHEN resumable THEN ${Download.Status.PAUSED} " +
            "ELSE ${Download.Status.FAILED} END, error = CASE WHEN resumable THEN NULL ELSE :error END " +
            "WHERE status = ${Download.Status.IN_PROGRESS} AND time < :before")
    suspend fun markInterrupted(before: Long, error: String)

    @Delete
    suspend fun delete(download: Download)

    @Query("DELETE FROM downloads WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
