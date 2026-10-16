package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * A FrameLayout that knows whether the user touches its children or has just done so: scrolling then
 * comes from the user (a drag, or the fling after it), not from the page itself.
 */
class TouchTrackingFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private var touching = false
    private var lastTouchEnd = 0L

    /** A finger is down, or was lifted less than [FLING_TIME] ago */
    val isTouchedRecently: Boolean
        get() = touching || SystemClock.uptimeMillis() - lastTouchEnd < FLING_TIME

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                lastTouchEnd = SystemClock.uptimeMillis()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    companion object {
        private const val FLING_TIME = 1500L
    }
}
