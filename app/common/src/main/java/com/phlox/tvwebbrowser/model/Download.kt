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
    /**
     * The video stream the download saves as one file ([StreamSpec.toJson]); null for a file downloaded as it is.
     * Its [url] is the address of the playlist of the stream.
     */
    @ColumnInfo(name = "stream")
    var streamSpec: String? = null

    //non-db fields
    @Ignore
    var operationAfterDownload = OperationAfterDownload.NOP
    @Ignore
    var cookies: String? = null
    /** The Referer header of the requests, also for incognito downloads that do not keep [referer] */
    @Ignore
    var refererHeader: String? = null
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

    /** Values of [error] the screens explain; other values are shown as they are (HTTP 404) */
    object Error {
        /** The process of the download service died during the download */
        const val INTERRUPTED = "interrupted"
        const val CONNECTION = "connection"
        const val STORAGE = "storage"
        /** The system stopped the download service, it runs too long */
        const val TIMEOUT = "timeout"
        /** A live stream has no end to save */
        const val LIVE = "live"
        /** The stream is protected (DRM) */
        const val PROTECTED = "protected"
        /** The stream has a format the file can not hold (a codec, a container) */
        const val FORMAT = "format"

        fun http(code: Int) = "HTTP $code"
    }

    constructor(url: String, filename: String, filepath: String?, operationAfterDownload: OperationAfterDownload,
                mimeType: String?, referer: String?, userAgentString: String?,
                stream: InputStream?, size: Long = 0L) : this() {
        this.url = url
        this.filename = filename
        this.filepath = filepath ?: ""
        this.operationAfterDownload = operationAfterDownload
        this.mimeType = mimeType
        this.referer = referer
        this.refererHeader = referer
        this.userAgentString = userAgentString
        this.stream = stream
        this.size = size
    }
}
