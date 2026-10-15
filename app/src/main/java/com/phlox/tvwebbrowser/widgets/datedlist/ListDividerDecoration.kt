package com.phlox.tvwebbrowser.widgets.datedlist

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.utils.themeColor

/** A divider under each row but the last one, like the dividers of a ListView */
class ListDividerDecoration(private val recyclerView: RecyclerView, adapter: RecyclerView.Adapter<*>) : RecyclerView.ItemDecoration() {
    private val paint = Paint().apply { color = recyclerView.context.themeColor(R.attr.tbColorDivider) }
    private val height = recyclerView.resources.getDimensionPixelSize(R.dimen.list_divider_height)

    init {
        //the row that was the last one gets its divider when rows are added after it
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = invalidate()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = invalidate()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = invalidate()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = invalidate()
        })
    }

    private fun invalidate() {
        if (!recyclerView.isComputingLayout) recyclerView.invalidateItemDecorations()
    }

    private fun hasDivider(parent: RecyclerView, view: View): Boolean {
        val position = parent.getChildAdapterPosition(view)
        return position != RecyclerView.NO_POSITION && position < (parent.adapter?.itemCount ?: 0) - 1
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.set(0, 0, 0, if (hasDivider(parent, view)) height else 0)
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (!hasDivider(parent, child)) continue
            val top = child.bottom + child.translationY
            c.drawRect(parent.paddingLeft.toFloat(), top, (parent.width - parent.paddingRight).toFloat(), top + height, paint)
        }
    }
}
