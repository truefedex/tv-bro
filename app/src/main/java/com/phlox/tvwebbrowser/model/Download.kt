package com.phlox.tvwebbrowser.model

import com.phlox.asql.annotations.DBColumn
import com.phlox.asql.annotations.DBIgnore
import com.phlox.asql.annotations.DBTable
import com.phlox.asql.annotations.MarkMode
import java.io.Serializable

/**
 * Created by PDT on 23.01.2017.
 */

@DBTable(name = "downloads", markMode = MarkMode.ALL_EXCEPT_IGNORED)
class Download: Serializable {
    @DBColumn(primaryKey = true)
    var id: Long = 0
    var time: Long = 0
    var filename: String? = null
    var filepath: String? = null
    var url: String? = null

    @Volatile
    var size: Long = 0
    @Volatile
    var bytesReceived: Long = 0
    @DBIgnore
    var operationAfterDownload = OperationAfterDownload.NOP

    //non-db fields
    @DBIgnore
    @Volatile
    var cancelled: Boolean = false
    @DBIgnore
    var isDateHeader = false//user for displaying date headers inside list view

    public enum class OperationAfterDownload {
        NOP, INSTALL
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
