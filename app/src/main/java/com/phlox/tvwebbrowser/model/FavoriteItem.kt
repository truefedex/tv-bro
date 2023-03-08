package com.phlox.tvwebbrowser.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Created by PDT on 09.09.2016.
 */
@Entity(tableName = "favorites", indices = arrayOf(
    Index(value = ["PARENT"], name = "favorites_parent_idx"),
    Index(value = ["HOME_PAGE_BOOKMARK"], name = "favorites_home_page_bookmark_idx")
    ))
class FavoriteItem {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "ID") var id: Long = 0
    @ColumnInfo(name = "TITLE") var title: String? = null
    @ColumnInfo(name = "URL") var url: String? = null
    @ColumnInfo(name = "PARENT") var parent: Long? = 0
    var favicon: String? = null
    @ColumnInfo(name = "HOME_PAGE_BOOKMARK") var homePageBookmark: Boolean = false
    @ColumnInfo(name = "I_ORDER") var order: Int = 0
    val isFolder: Boolean
        get() = url == null
}
