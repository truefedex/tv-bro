package com.phlox.tvwebbrowser.model

import com.phlox.asql.annotations.DBTable
import com.phlox.asql.annotations.MarkMode

/**
 * Created by PDT on 09.09.2016.
 */
@DBTable(name = "favorites", markMode = MarkMode.ALL_EXCEPT_IGNORED)
class FavoriteItem {
    var id: Long = 0
    var title: String? = null
    var url: String? = null
    var parent: Long = 0

    val isFolder: Boolean
        get() = url == null
}
