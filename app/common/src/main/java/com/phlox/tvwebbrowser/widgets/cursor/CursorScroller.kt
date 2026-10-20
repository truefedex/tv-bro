package com.phlox.tvwebbrowser.widgets.cursor

import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * Scrolls [surface] when the cursor pushes against an edge of the screen. A mouse scrolls with
 * wheel events (the element under the cursor scrolls, as on a desktop computer). Otherwise this
 * scrolls the view itself, then asks the [customScrollCallback] of the engine, and falls back to
 * the "scroll hack": an emulated touch drag (through [touchEmulator]) that pages with their own
 * scroll handling react to.
 */
class CursorScroller(
    private val surface: View,
    private val touchEmulator: TouchEmulator,
) {
    /** The active pointer emulator, kept in sync with [CursorDrawerDelegate.pointerMode] */
    var pointer: PointerEmulator = touchEmulator
    var customScrollCallback: CursorDrawerDelegate.CustomScrollCallback? = null

    private var scrollHackStarted = false
    private val scrollHackCoords = PointF()
    private val scrollHackActiveRect = Rect()

    fun onSizeChanged(w: Int, h: Int) {
        scrollHackActiveRect.set(0, 0, w, h)
        scrollHackActiveRect.inset(SCROLL_HACK_PADDING, SCROLL_HACK_PADDING)
    }

    /** Ends the touch stream of the scroll hack, if one is in flight */
    fun cancelScrollHack() {
        if (scrollHackStarted) {
            touchEmulator.dispatch(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_CANCEL)
            scrollHackStarted = false
        }
    }

    /**
     * @param allowScrollHack false while the touch stream of a click is active: the scroll hack
     * must not open a second synthetic gesture on top of it
     */
    fun scrollBy(scrollX: Int, scrollY: Int, cursorPosition: PointF, allowScrollHack: Boolean) {
        if (scrollX == 0 && scrollY == 0) {
            return
        }

        // A mouse scrolls with wheel events, which need neither a gesture nor the hack
        if (pointer.scroll(cursorPosition.x, cursorPosition.y, scrollX.toFloat(), scrollY.toFloat())) {
            return
        }

        if ((scrollX != 0 && surface.canScrollHorizontally(scrollX)) ||
            (scrollY != 0 && surface.canScrollVertically(scrollY))
        ) {
            surface.scrollTo(surface.scrollX + scrollX, surface.scrollY + scrollY)
        } else if (customScrollCallback?.onScroll(scrollX, scrollY) == true) {
            return
        } else if (USE_SCROLL_HACK && allowScrollHack) {
            var justStarted = false
            if (!scrollHackStarted) {
                scrollHackCoords.set(
                    bound(cursorPosition.x, scrollHackActiveRect.left.toFloat(), scrollHackActiveRect.right.toFloat()),
                    bound(cursorPosition.y, scrollHackActiveRect.top.toFloat(), scrollHackActiveRect.bottom.toFloat()))
                touchEmulator.dispatch(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_DOWN)
                scrollHackStarted = true
                justStarted = true
            }
            scrollHackCoords.x -= scrollX
            scrollHackCoords.y -= scrollY
            if (scrollHackCoords.x < scrollHackActiveRect.left || scrollHackCoords.x >= scrollHackActiveRect.right ||
                scrollHackCoords.y < scrollHackActiveRect.top || scrollHackCoords.y >= scrollHackActiveRect.bottom) {
                scrollHackCoords.x += scrollX
                scrollHackCoords.y += scrollY
                touchEmulator.dispatch(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_CANCEL)
                scrollHackStarted = false
                if (!justStarted) {
                    scrollBy(scrollX, scrollY, cursorPosition, allowScrollHack)
                }
                return
            }
            touchEmulator.dispatch(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_MOVE)
        }
    }

    /** Unlike coerceIn, does not throw when a small surface leaves the active rect inverted */
    private fun bound(value: Float, min: Float, max: Float): Float = when {
        value > max -> max
        value < min -> min
        else -> value
    }

    companion object {
        private const val USE_SCROLL_HACK = true
        private const val SCROLL_HACK_PADDING = 300
    }
}
