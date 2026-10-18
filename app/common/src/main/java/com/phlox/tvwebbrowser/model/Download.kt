package com.phlox.tvwebbrowser.model

import androidx.room.*
import java.io.InputStream

/**
 * Created by PDT on 23.01.2017.
 */

@Entity(tableName = "downloads", indices = arrayOf(Index(value = ["time"], name = "downloads_time_idx"),
        Index(value = ["filename"], name = "downloads_filename_idx")))
class Download() {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
    var time: Long = 0
    var filename: String = ""
    var filepath: String = ""
    var url: String = ""

    /** The full size of the file, 0 when it is not known */
    @Volatile
    var size: Long = 0
    @Volatile @ColumnInfo(name = "bytes_received")
    var bytesReceived: Long = 0
    /** One of [Status]. The default of the column is [Status.DONE], for the rows older than the column */
    @Volatile @ColumnInfo(defaultValue = "3")
    var status: Int = Status.IN_PROGRESS
    @ColumnInfo(name = "mime_type")
    var mimeType: String? = null
    /** The url of the page the download was started from (none for incognito downloads) */
    var referer: String? = null
    @ColumnInfo(name = "user_agent")
    var userAgentString: String? = null
    /** A short reason of the failure, for a [Status.FAILED] download */
    var error: String? = null
    /** Validators of the file on the server, for resuming the download */
    var etag: String? = null
    @ColumnInfo(name = "last_modified")
    var lastModified: String? = null
    /** The server accepts byte ranges, so the download can be paused and resumed */
    @ColumnInfo(defaultValue = "0")
    var resumable: Boolean = false

    //non-db fields
    @Ignore
    var operationAfterDownload = OperationAfterDownload.NOP
    @Ignore
    @Volatile
    var cancelled: Boolean = false
    @Ignore
    var cookies: String? = null
    @Ignore
    var base64BlobData: String? = null
    @Ignore
    var stream: InputStream? = null

    enum class OperationAfterDownload {
        NOP, INSTALL
    }

    /** Values of [status] */
    object Status {
        const val IN_PROGRESS = 1
        const val PAUSED = 2
        const val DONE = 3
        const val FAILED = 4
        const val CANCELLED = 5
    }

    constructor(url: String, filename: String, filepath: String?, operationAfterDownload: OperationAfterDownload,
                mimeType: String?, referer: String?, userAgentString: String?, base64BlobData: String?,
                stream: InputStream?, size: Long = 0L) : this() {
        this.url = url
        this.filename = filename
        this.filepath = filepath ?: ""
        this.operationAfterDownload = operationAfterDownload
        this.mimeType = mimeType
        this.referer = referer
        this.userAgentString = userAgentString
        this.base64BlobData = base64BlobData
        this.stream = stream
        this.size = size
    }
}
