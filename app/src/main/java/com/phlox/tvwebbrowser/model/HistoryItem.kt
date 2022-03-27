package com.phlox.tvwebbrowser.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Created by fedex on 28.12.16.
 */

@Entity(tableName = "history", indices = arrayOf(Index(value = ["time"], name = "history_time_idx"),
        Index(value = ["title"], name = "history_title_idx"), Index(value = ["url"], name = "history_url_idx")))
class HistoryItem {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
    var time: Long = 0
    var title: String = ""
    var url: String = ""
    var favicon: String? = null
    var incognito: Boolean? = null

    @Ignore
    var isDateHeader = false//used for displaying date headers inside list view
    @Ignore
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
