package com.phlox.tvwebbrowser.widgets.datedlist

import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.utils.themeColor

/**
 * Draws the header of the day of the top row over the list (it is pushed up by the next header),
 * with a shadow under it, like the pinned sections of a ListView did. Nothing is pinned while the
 * header row itself is at the top.
 */
class StickyHeaderDecoration(private val adapter: DatedListAdapter<*>) : RecyclerView.ItemDecoration() {
    private var header: DatedListAdapter.HeaderViewHolder? = null
    private var shadow: GradientDrawable? = null
    private var shadowHeight = 0

    /** The height of the pinned header (0 when nothing is pinned yet), to keep focused rows out from under it */
    var pinnedHeight = 0
        private set

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val top = topChild(parent) ?: return
        val topPosition = parent.getChildAdapterPosition(top)
        if (topPosition == RecyclerView.NO_POSITION) return
        if (adapter.isHeader(topPosition) && top.top >= parent.paddingTop) return
        val headerPosition = adapter.headerPositionFor(topPosition)
        if (headerPosition < 0) return
        val view = headerView(parent, (adapter.row(headerPosition) as DatedRow.Header).day)
        pinnedHeight = view.height
        val shadow = shadow(parent)

        //the next header pushes the pinned one up, and its shadow shrinks while it comes closer
        var offset = 0
        var shadowHeight = this.shadowHeight
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position > headerPosition && position != RecyclerView.NO_POSITION && adapter.isHeader(position)) {
                val distance = child.top - (parent.paddingTop + view.height)
                if (distance < 0) offset = distance
                shadowHeight = shadowHeight.coerceAtMost(distance.coerceAtLeast(0))
                break
            }
        }

        val save = c.save()
        c.clipRect(parent.paddingLeft, parent.paddingTop, parent.paddingLeft + view.width,
            parent.paddingTop + view.height + shadowHeight)
        c.translate(parent.paddingLeft.toFloat(), (parent.paddingTop + offset).toFloat())
        view.draw(c)
        if (shadowHeight > 0) {
            shadow.setBounds(0, view.height, view.width, view.height + shadowHeight)
            shadow.draw(c)
        }
        c.restoreToCount(save)
    }

    private fun topChild(parent: RecyclerView): View? {
        var top: View? = null
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (top == null || child.top < top.top) top = child
        }
        return top
    }

    private fun headerView(parent: RecyclerView, day: Long): View {
        val holder = header ?: adapter.createHeaderViewHolder(parent).also { header = it }
        val width = parent.width - parent.paddingLeft - parent.paddingRight
        if (holder.day != day || holder.itemView.width != width) {
            holder.bind(day)
            val view = holder.itemView
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        return holder.itemView
    }

    private fun shadow(parent: RecyclerView): GradientDrawable = shadow ?: run {
        val color = parent.context.themeColor(R.attr.tbColorShadow)
        shadowHeight = (SHADOW_HEIGHT_DP * parent.resources.displayMetrics.density).toInt()
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            color, ColorUtils.setAlphaComponent(color, 0x50), ColorUtils.setAlphaComponent(color, 0)
        )).also { shadow = it }
    }

    companion object {
        private const val SHADOW_HEIGHT_DP = 8f
    }
}
