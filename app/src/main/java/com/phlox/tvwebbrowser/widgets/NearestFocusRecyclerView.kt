package com.phlox.tvwebbrowser.widgets

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
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

    /**
     * A row the layout manager laid out for the search (past the end of the list in view) comes as the row itself,
     * and a request of focus with Up gives it to its last focusable view (a button at its end): the view of the row
     * in line with the focused one gets it instead, as for rows already in view
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        if (focused == null || result !is ViewGroup || result.parent !== this || result.isFocusable ||
            focused.parent == null || !isAncestorOf(focused)) return result
        val rect = Rect()
        focused.getDrawingRect(rect)
        offsetDescendantRectToMyCoords(focused, rect)
        offsetRectIntoDescendantCoords(result, rect)
        return FocusFinder.getInstance().findNextFocusFromRect(result, rect, direction) ?: result
    }

    private fun isAncestorOf(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
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
