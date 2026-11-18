package com.phlox.tvwebbrowser.widgets.cursor

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The mouse events of the cursor reach the view under them in the right half of the layout too: a ViewGroup
 * finds the child of a mouse event by its cursor position, which an event made by the app does not have right
 * on a display with an overridden size.
 */
@RunWith(RobolectricTestRunner::class)
class CursorLayoutMouseDispatchTest {
    private class RecordingView(activity: Activity) : View(activity) {
        val generic = ArrayList<Pair<Int, Float>>()
        val touches = ArrayList<Pair<Int, Float>>()

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            generic += event.actionMasked to event.x
            return true
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            touches += event.actionMasked to event.x
            return true
        }
    }

    private fun layout(): Pair<CursorLayout, RecordingView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val layout = CursorLayout(activity)
        val page = RecordingView(activity)
        layout.addView(page)
        layout.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        )
        layout.layout(0, 0, 1920, 1080)
        return layout to page
    }

    private fun mouseEvent(action: Int, x: Float, y: Float): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().also { it.toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().also { it.x = x; it.y = y })
        val time = SystemClock.uptimeMillis()
        return MotionEvent.obtain(time, time, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0)
    }

    @Test
    fun clicksInTheRightHalfReachThePage() {
        val (layout, page) = layout()
        assertTrue(layout.dispatchSyntheticTouch(mouseEvent(MotionEvent.ACTION_DOWN, 1500f, 300f)))
        assertTrue(layout.dispatchSyntheticGenericMotion(mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, 1500f, 300f)))
        assertEquals(listOf(MotionEvent.ACTION_DOWN to 1500f), page.touches)
        assertEquals(listOf(MotionEvent.ACTION_BUTTON_PRESS to 1500f), page.generic)
    }

    @Test
    fun hoverEntersOnceAndExits() {
        val (layout, page) = layout()
        layout.dispatchSyntheticGenericMotion(mouseEvent(MotionEvent.ACTION_HOVER_MOVE, 1500f, 300f))
        layout.dispatchSyntheticGenericMotion(mouseEvent(MotionEvent.ACTION_HOVER_MOVE, 1600f, 300f))
        layout.dispatchSyntheticGenericMotion(mouseEvent(MotionEvent.ACTION_HOVER_EXIT, 1600f, 300f))
        assertEquals(
            listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_HOVER_EXIT),
            page.generic.map { it.first }
        )
    }
}
