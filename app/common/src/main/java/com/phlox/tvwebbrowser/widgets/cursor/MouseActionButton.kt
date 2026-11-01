package com.phlox.tvwebbrowser.widgets.cursor

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Sets the action button of a synthetic mouse event, which `MotionEvent.obtain` leaves 0. The setter
 * is hidden (`@TestApi`), so it is reached by reflection: allowed up to Android 8.1 and from
 * Android 11 (`@UnsupportedAppUsage`), most likely blocked on Android 9 and 10. [canSet] tells
 * whether it is there and works.
 */
object MouseActionButton {
    private const val TAG = "MouseActionButton"

    private val setter: Method? by lazy {
        try {
            MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
                .takeIf { works(it) }
        } catch (e: Throwable) {
            Log.i(TAG, "setActionButton is not available: $e")
            null
        }
    }

    val canSet: Boolean
        get() = setter != null

    /** Sets the action button of [event] if [canSet] */
    fun set(event: MotionEvent, button: Int) {
        val setter = setter ?: return
        try {
            setter.invoke(event, button)
        } catch (e: Throwable) {
            Log.w(TAG, "setActionButton failed", e)
        }
    }

    private fun works(setter: Method): Boolean {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            time, time, MotionEvent.ACTION_BUTTON_PRESS, 1,
            arrayOf(MotionEvent.PointerProperties().also { it.toolType = MotionEvent.TOOL_TYPE_MOUSE }),
            arrayOf(MotionEvent.PointerCoords()),
            0, MotionEvent.BUTTON_PRIMARY, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try {
            setter.invoke(event, MotionEvent.BUTTON_PRIMARY)
            return event.actionButton == MotionEvent.BUTTON_PRIMARY
        } finally {
            event.recycle()
        }
    }
}
