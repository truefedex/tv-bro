package com.phlox.tvwebbrowser.widgets.cursor

import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Zooms the page with an emulated two finger pinch at the center of [surface]. Always fingers,
 * whatever the [PointerMode] of the cursor: a mouse has no pinch.
 *
 * The events sequence: ACTION_DOWN of the first finger, ACTION_POINTER_2_DOWN of both, ACTION_MOVE
 * of both until they reach their end points, ACTION_POINTER_2_UP, ACTION_UP.
 * See https://stackoverflow.com/questions/11523423/how-to-generate-zoom-pinch-gesture-for-testing-for-android
 */
class PinchZoomGesture(private val surface: View, private val target: PointerTarget) {
    private var startTime = 0L
    private var zoomIn = true

    /** Does nothing while another pinch is in progress */
    fun start(zoomIn: Boolean) {
        if (startTime != 0L) {
            return
        }
        this.zoomIn = zoomIn
        startTime = SystemClock.uptimeMillis()
        val (start1, start2) = startPoints()
        dispatch(MotionEvent.ACTION_DOWN, 1, start1, start2, startTime)
        @Suppress("DEPRECATION")
        dispatch(MotionEvent.ACTION_POINTER_2_DOWN, 2, start1, start2, startTime)
        surface.post(stepRunnable)
    }

    private val stepRunnable: Runnable = object : Runnable {
        override fun run() {
            if (startTime == 0L) {
                return
            }
            val (start1, start2) = startPoints()
            val (end1, end2) = endPoints()
            val now = SystemClock.uptimeMillis()
            if (now - startTime < DURATION) {
                val progress = (now - startTime).toFloat() / DURATION
                val p1 = PointF(start1.x + (end1.x - start1.x) * progress, start1.y + (end1.y - start1.y) * progress)
                val p2 = PointF(start2.x + (end2.x - start2.x) * progress, start2.y + (end2.y - start2.y) * progress)
                dispatch(MotionEvent.ACTION_MOVE, 2, p1, p2, now)
                surface.post(this)
            } else {
                @Suppress("DEPRECATION")
                dispatch(MotionEvent.ACTION_POINTER_2_UP, 2, end1, end2, now)
                dispatch(MotionEvent.ACTION_UP, 1, end1, end2, now)
                startTime = 0
            }
        }
    }

    private fun near(): Pair<PointF, PointF> {
        val dx = ZOOM_FACTOR / 4f * surface.height
        return Pair(
            PointF(surface.width / 2f - dx, surface.height / 2f - dx),
            PointF(surface.width / 2f + dx, surface.height / 2f + dx)
        )
    }

    private fun far(): Pair<PointF, PointF> {
        val dx = ZOOM_FACTOR / 2f * surface.height
        return Pair(
            PointF(surface.width / 2f - dx, surface.height / 2f - dx),
            PointF(surface.width / 2f + dx, surface.height / 2f + dx)
        )
    }

    /** Zooming in spreads the fingers, zooming out brings them together */
    private fun startPoints() = if (zoomIn) near() else far()

    private fun endPoints() = if (zoomIn) far() else near()

    private fun dispatch(action: Int, pointerCount: Int, p1: PointF, p2: PointF, eventTime: Long) {
        val properties = arrayOf(
            MotionEvent.PointerProperties().also {
                it.id = 0
                it.toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().also {
                it.id = 1
                it.toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        //the pressure and the size must be set, or the gesture does not work
        val coords = arrayOf(
            MotionEvent.PointerCoords().also {
                it.x = p1.x
                it.y = p1.y
                it.pressure = 1f
                it.size = 1f
            },
            MotionEvent.PointerCoords().also {
                it.x = p2.x
                it.y = p2.y
                it.pressure = 1f
                it.size = 1f
            }
        )
        val event = MotionEvent.obtain(
            startTime, eventTime, action, pointerCount, properties, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        try {
            target.dispatchSyntheticTouch(event)
        } finally {
            event.recycle()
        }
    }

    companion object {
        private const val DURATION = 1000
        private const val ZOOM_FACTOR = 0.1f
    }
}
