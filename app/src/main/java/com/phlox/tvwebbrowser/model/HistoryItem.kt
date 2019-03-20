package com.phlox.tvwebbrowser.model

import com.phlox.asql.annotations.DBIgnore
import com.phlox.asql.annotations.DBTable
import com.phlox.asql.annotations.MarkMode

import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Created by fedex on 28.12.16.
 */

@DBTable(name = "history", markMode = MarkMode.ALL_EXCEPT_IGNORED)
class HistoryItem {
    var id: Long = 0
    var time: Long = 0
    var title: String? = null
    var url: String? = null

    @DBIgnore
    var isDateHeader = false//used for displaying date headers inside list view
    @DBIgnore
    var selected = false

    companion object {

        fun createDateHeaderInfo(time: Long): HistoryItem {
            val hi = HistoryItem()
            hi.time = time
            hi.isDateHeader = true
            return hi
        }
    }
}
