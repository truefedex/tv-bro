package com.phlox.tvwebbrowser.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Created by PDT on 09.09.2016.
 */
@Entity(tableName = "favorites")
class FavoriteItem {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "ID") var id: Long = 0
    @ColumnInfo(name = "TITLE") var title: String? = null
    @ColumnInfo(name = "URL") var url: String? = null
    @ColumnInfo(name = "PARENT") var parent: Long? = 0
    var favicon: String? = null
    val isFolder: Boolean
        get() = url == null
}
