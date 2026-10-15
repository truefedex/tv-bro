package com.phlox.tvwebbrowser.widgets

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * A RecyclerView into which focus coming from another view (for example a button with nextFocus... set to
 * the list) goes to the row nearest to that view, like into a ListView, and not to the first row.
 */
class NearestFocusRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    init {
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (previouslyFocusedRect == null) {
            //focus requested while the layout was pending is restored after it without a rect: keep the row that has it
            val focused = focusedChild
            if (focused != null && focused.requestFocus(direction)) return true
        } else if (!isComputingLayout) {
            val from = previouslyFocusedRect.exactCenterY()
            var nearest: View? = null
            var nearestDistance = Float.MAX_VALUE
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                //rows being removed are still here until the next layout
                if (!child.isFocusable || child.visibility != VISIBLE || getChildAdapterPosition(child) == NO_POSITION) continue
                val distance = abs((child.top + child.bottom) / 2f - from)
                if (distance < nearestDistance) {
                    nearest = child
                    nearestDistance = distance
                }
            }
            if (nearest?.requestFocus(direction) == true) return true
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }
}
