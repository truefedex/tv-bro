package com.phlox.tvwebbrowser.widgets.cursor

import android.os.SystemClock
import android.view.MotionEvent

/**
 * Emulates a single finger on [target]. Remembers the downTime of the last ACTION_DOWN so the
 * following MOVE/UP/CANCEL events belong to the same gesture. Only one stream can be active at a
 * time; the callers (the clicks of [CursorDrawerDelegate] and the scroll hack of [CursorScroller])
 * do not interleave their gestures.
 */
class TouchEmulator(private val target: PointerTarget) : PointerEmulator {
    private var downTime: Long = 0L

    fun dispatch(x: Float, y: Float, action: Int) {
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = SystemClock.uptimeMillis()
        }
        val properties = arrayOf(MotionEvent.PointerProperties().also {
            it.id = 0
            it.toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().also {
            it.x = x
            it.y = y
            it.pressure = 1f
            it.size = 1f
        })
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        try {
            target.dispatchSyntheticTouch(event)
        } finally {
            event.recycle()
        }
    }

    /** A finger that does not touch the screen has nothing to report */
    override fun move(x: Float, y: Float, pressed: Boolean) {
        if (pressed) {
            dispatch(x, y, MotionEvent.ACTION_MOVE)
        }
    }

    override fun press(x: Float, y: Float) = dispatch(x, y, MotionEvent.ACTION_DOWN)

    override fun release(x: Float, y: Float) = dispatch(x, y, MotionEvent.ACTION_UP)

    override fun cancel(x: Float, y: Float) = dispatch(x, y, MotionEvent.ACTION_CANCEL)

    /** A finger has no wheel: [CursorScroller] scrolls the view or uses the scroll hack */
    override fun scroll(x: Float, y: Float, dx: Float, dy: Float): Boolean = false

    override fun detach() = Unit
}
