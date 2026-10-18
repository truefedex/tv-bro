package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.dao.DownloadDao
import kotlinx.coroutines.flow.Flow

/**
 * Downloads, the active ones and the finished ones. The download service (in its own process)
 * writes them, the screens observe them.
 */
class DownloadsRepository(private val dao: DownloadDao) {
    /** All the downloads, the latest first */
    fun observeAll(): Flow<List<Download>> = dao.observeAll()

    suspend fun get(id: Long): Download? = dao.get(id)

    suspend fun withStatus(status: Int): List<Download> = dao.withStatus(status)

    /** @return the id */
    suspend fun insert(download: Download): Long = dao.insert(download)

    suspend fun update(download: Download) = dao.update(download)

    suspend fun updateProgress(id: Long, bytesReceived: Long, size: Long) =
        dao.updateProgress(id, bytesReceived, size)

    suspend fun setStatus(id: Long, status: Int, error: String? = null) = dao.setStatus(id, status, error)

    /**
     * The downloads left in progress by a process that died, those started [before] it was started
     * again: they become paused if they can be resumed, failed with [error] otherwise
     */
    suspend fun markInterrupted(before: Long, error: String) = dao.markInterrupted(before, error)

    suspend fun delete(download: Download) = dao.delete(download)

    suspend fun delete(ids: List<Long>) = dao.delete(ids)
}
