package com.phlox.tvwebbrowser.widgets.datedlist

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Scrolls a row that gets focus so that it is not under the pinned header of [headers] */
class StickyHeaderLayoutManager(context: Context, private val headers: StickyHeaderDecoration) : LinearLayoutManager(context) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect,
                                               immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        val withHeader = Rect(rect).apply { top -= headers.pinnedHeight }
        return super.requestChildRectangleOnScreen(parent, child, withHeader, immediate, focusedChildVisible)
    }
}
