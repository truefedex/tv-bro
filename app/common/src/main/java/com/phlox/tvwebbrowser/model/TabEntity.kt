package com.phlox.tvwebbrowser.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A snapshot of a tab ([BrowserTab]) as it is kept in the database.
 */
@Entity(tableName = "tabs")
data class TabEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String = "",
    val title: String = "",
    val selected: Boolean = false,
    val thumbnailHash: String? = null,
    val faviconHash: String? = null,
    val incognito: Boolean = false,
    val position: Int = 0,
    @ColumnInfo(name = "wv_state_file")
    val wvStateFileName: String? = null,
    val adblock: Boolean? = null,
    val scale: Float? = null
)
