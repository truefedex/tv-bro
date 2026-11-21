package com.phlox.tvwebbrowser.widgets.datedlist

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Scrolls a row that gets focus so that it is not under the pinned header of [headers].
 *
 * A row that gets focus while it is wholly out of view (the list lays out the next row past its end when the
 * D-pad goes there, and quick presses go further while the smooth scroll is on its way) is scrolled to at once:
 * the next layout lays out only the rows in view, and it dropped the focused one, the focus then going to the
 * first view of the window, under the screen of the list.
 */
class StickyHeaderLayoutManager(context: Context, private val headers: StickyHeaderDecoration) : LinearLayoutManager(context) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect,
                                               immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        val withHeader = Rect(rect).apply { top -= headers.pinnedHeight }
        val outOfView = child.bottom <= parent.paddingTop + headers.pinnedHeight ||
                child.top >= parent.height - parent.paddingBottom
        return super.requestChildRectangleOnScreen(parent, child, withHeader, immediate || outOfView, focusedChildVisible)
    }
}
