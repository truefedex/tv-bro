package com.phlox.tvwebbrowser.data

import androidx.annotation.WorkerThread
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.dao.DownloadDao

/**
 * Downloads, the active ones and the history.
 */
class DownloadsRepository(private val dao: DownloadDao) {
    /** A page of the downloads, the latest first */
    suspend fun page(offset: Long): List<Download> = dao.allByLimitOffset(offset)

    suspend fun delete(download: Download) = dao.delete(download)

    /** Blocking, for the download threads. @return the id */
    @WorkerThread
    fun insert(download: Download): Long = dao.insert(download)

    /** Blocking, for the download threads */
    @WorkerThread
    fun update(download: Download) = dao.update(download)
}
