package com.phlox.tvwebbrowser.model

import androidx.room.*
import java.io.Serializable

/**
 * Created by PDT on 23.01.2017.
 */

@Entity(tableName = "downloads", indices = arrayOf(Index(value = ["time"], name = "downloads_time_idx"),
        Index(value = ["filename"], name = "downloads_filename_idx")))
class Download {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
    var time: Long = 0
    var filename: String = ""
    var filepath: String = ""
    var url: String = ""

    @Volatile
    var size: Long = 0
    @Volatile @ColumnInfo(name = "bytes_received")
    var bytesReceived: Long = 0
    @Ignore
    var operationAfterDownload = OperationAfterDownload.NOP

    //non-db fields
    @Ignore
    @Volatile
    var cancelled: Boolean = false
    @Ignore
    var isDateHeader = false//user for displaying date headers inside list view
    @Ignore
    var mimeType: String? = null
    @Ignore
    var referer: String? = null

    enum class OperationAfterDownload {
        NOP, INSTALL
    }

    fun fillWith(intent: DownloadIntent) {
        url = intent.url
        filename = intent.fileName
        filepath = intent.fullDestFilePath ?: ""
        operationAfterDownload = intent.operationAfterDownload
        mimeType = intent.mimeType
        referer = intent.referer
    }

    companion object {
        val BROKEN_MARK: Long = -2
        val CANCELLED_MARK: Long = -3

        fun createDateHeaderInfo(time: Long): Download {
            val download = Download()
            download.time = time
            download.isDateHeader = true
            return download
        }
    }
}
