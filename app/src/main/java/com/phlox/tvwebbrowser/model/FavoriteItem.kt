package com.phlox.tvwebbrowser.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.*

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
    @ColumnInfo(name = "I_ORDER") var order: Int = 0//used currently only for home page bookmarks because they can have blank cells in the grid
    @ColumnInfo(name = "DEST_URL") var destUrl: String? = null//used for initial recommendations for home page bookmarks to store referral url
    @ColumnInfo(name = "DESCRIPTION") var description: String? = null//used for initial recommendations for home page bookmarks if they have description
    @ColumnInfo(name = "VALID_UNTIL") var validUntil: Date? = null//used for initial recommendations for home page bookmarks if they have description
    val isFolder: Boolean
        get() = url == null
}
