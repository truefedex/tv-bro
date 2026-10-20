package com.phlox.tvwebbrowser.widgets.cursor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Emulates a mouse on [target]: hover moves while no button is held, a primary button
 * press/drag/release stream while it is, and wheel events for scrolling. The page sees
 * `mousemove`, `mousedown`, `mouseup`, `click` and `wheel` with `pointerType: "mouse"` and a real
 * `:hover` state, which is what pages made for desktop computers expect.
 *
 * The pointer stream (DOWN/MOVE/UP/CANCEL) goes through the touch dispatch path, while hover, wheel
 * and the button change events go through the generic motion path: the same split a physical mouse
 * produces on Android. The button change events are needed by WebView, see [dispatchButtonChange].
 *
 * GeckoView on a device in the television UI mode treats mouse events of the touch path as touches
 * (`PanZoomController.treatMouseAsTouch`, as for a physical mouse there), so there a click arrives
 * in the page as a tap, while hover and wheel stay mouse events.
 */
class MouseEmulator(private val target: PointerTarget) : PointerEmulator {
    private var downTime: Long = 0L
    private var hovering = false
    private var lastX = 0f
    private var lastY = 0f

    override fun move(x: Float, y: Float, pressed: Boolean) {
        remember(x, y)
        if (pressed) {
            dispatchPointer(x, y, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
        } else {
            hovering = true
            dispatchHoverOrScroll(x, y, MotionEvent.ACTION_HOVER_MOVE)
        }
    }

    override fun press(x: Float, y: Float) {
        remember(x, y)
        // No HOVER_EXIT here: on the web the pointer keeps hovering while a button is held, and an
        // exit would make the page fire mouseleave right before mousedown.
        downTime = SystemClock.uptimeMillis()
        dispatchPointer(x, y, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY)
        dispatchButtonChange(x, y, MotionEvent.ACTION_BUTTON_PRESS)
    }

    override fun release(x: Float, y: Float) {
        remember(x, y)
        dispatchButtonChange(x, y, MotionEvent.ACTION_BUTTON_RELEASE)
        dispatchPointer(x, y, MotionEvent.ACTION_UP, 0)
    }

    override fun cancel(x: Float, y: Float) {
        remember(x, y)
        // The button is released first, or the page is left believing it is still held
        dispatchButtonChange(x, y, MotionEvent.ACTION_BUTTON_RELEASE)
        dispatchPointer(x, y, MotionEvent.ACTION_CANCEL, 0)
    }

    /** A right click: press and release of the secondary button */
    override fun contextClick(x: Float, y: Float) {
        remember(x, y)
        downTime = SystemClock.uptimeMillis()
        dispatchPointer(x, y, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY)
        dispatchButtonChange(x, y, MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_SECONDARY)
        dispatchButtonChange(x, y, MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.BUTTON_SECONDARY)
        dispatchPointer(x, y, MotionEvent.ACTION_UP, 0)
    }

    /**
     * The event that makes WebView fire `mousedown`/`mouseup` in the page: Chromium ignores a bare
     * mouse ACTION_DOWN/ACTION_UP and takes the button change from ACTION_BUTTON_PRESS/RELEASE.
     * `MotionEvent.obtain` cannot set `actionButton`, so the button is carried in `buttonState`,
     * on both press and release, which is where Chromium reads it from.
     */
    private fun dispatchButtonChange(x: Float, y: Float, action: Int, button: Int = MotionEvent.BUTTON_PRIMARY) {
        dispatch(x, y, action, button, 0f, 0f, touch = false)
    }

    override fun scroll(x: Float, y: Float, dx: Float, dy: Float): Boolean {
        remember(x, y)
        val pixelsPerTick = target.wheelScrollFactor
        if (pixelsPerTick <= 0f) return false
        // Wheel axes are measured in "clicks" and point away from the scroll direction:
        // scrolling the page down (positive dy) is a negative VSCROLL
        dispatchHoverOrScroll(
            x, y, MotionEvent.ACTION_SCROLL,
            vScroll = -dy / pixelsPerTick,
            hScroll = -dx / pixelsPerTick
        )
        return true
    }

    /** Clears the hover state of the page, so nothing stays highlighted after the cursor goes away */
    override fun detach() {
        if (!hovering) return
        hovering = false
        dispatchHoverOrScroll(lastX, lastY, MotionEvent.ACTION_HOVER_EXIT)
    }

    private fun remember(x: Float, y: Float) {
        lastX = x
        lastY = y
    }

    private fun dispatchPointer(x: Float, y: Float, action: Int, buttonState: Int) {
        dispatch(x, y, action, buttonState, 0f, 0f, touch = true)
    }

    private fun dispatchHoverOrScroll(
        x: Float,
        y: Float,
        action: Int,
        vScroll: Float = 0f,
        hScroll: Float = 0f,
    ) {
        dispatch(x, y, action, 0, vScroll, hScroll, touch = false)
    }

    private fun dispatch(
        x: Float,
        y: Float,
        action: Int,
        buttonState: Int,
        vScroll: Float,
        hScroll: Float,
        touch: Boolean,
    ) {
        val eventTime = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().also {
            it.id = 0
            it.toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().also {
            it.x = x
            it.y = y
            it.pressure = if (buttonState != 0) 1f else 0f
            it.size = 1f
            if (vScroll != 0f) it.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            if (hScroll != 0f) it.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
        })
        val event = MotionEvent.obtain(
            // Hover and wheel events belong to no gesture, so they carry the current time as their
            // downTime (GeckoView drops wheel events whose downTime goes back); the others reuse
            // the downTime of the press that opened them
            if (touch) downTime else eventTime, eventTime, action, 1, properties, coords,
            0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try {
            if (touch) {
                target.dispatchSyntheticTouch(event)
            } else {
                target.dispatchSyntheticGenericMotion(event)
            }
        } finally {
            event.recycle()
        }
    }
}
