package com.phlox.tvwebbrowser.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey


@Entity(tableName = "hosts", indices = arrayOf(
    Index(value = ["host_name"], name = "hosts_name_idx", unique = true)
))
class HostConfig(
    @ColumnInfo(name = "host_name")
    val hostName: String
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
    @ColumnInfo(name = "popup_block_level")
    var popupBlockLevel: Int? = null
    var favicon: String? = null
    /** Ads blocking on this host; null if it follows the global setting */
    var adblock: Boolean? = null
    /** Pages of this host load as on a desktop computer (true) or a phone (false); null if it follows the default */
    @ColumnInfo(name = "desktop_view")
    var desktopView: Boolean? = null

    companion object {
        const val POPUP_BLOCK_NONE = 0
        const val POPUP_BLOCK_DIALOGS = 1
        const val POPUP_BLOCK_NEW_AUTO_OPENED_TABS = 2
        const val POPUP_BLOCK_NEW_TABS_EVEN_BY_USER_GESTURE = 3

        const val DEFAULT_BLOCK_POPUPS_VALUE = POPUP_BLOCK_NEW_AUTO_OPENED_TABS
    }
}