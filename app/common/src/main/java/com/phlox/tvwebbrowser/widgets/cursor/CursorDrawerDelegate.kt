package com.phlox.tvwebbrowser.widgets.cursor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config

/**
 * The virtual cursor: movement, the selection keys, visibility timing, press and grab state.
 * Movement comes as a direction vector ([setDirection]), not as keys: a `DPADNavigationEventsAdapter`
 * makes it of the D-pad keys, the hat and the sticks of game controllers.
 * The concrete work goes to collaborators: [CursorRenderer] (drawing), a [PointerEmulator] (the
 * synthetic click/drag stream: a finger or a mouse, see [pointerMode]), [CursorScroller] (scrolling
 * at the edges, including the scroll hack) and [PinchZoomGesture].
 *
 * [surface] is the view the cursor is drawn over and moves in; synthetic events go to [target].
 */
class CursorDrawerDelegate(val context: Context, val surface: View, private val target: PointerTarget) {
    private val touchEmulator = TouchEmulator(target)
    private val mouseEmulator = MouseEmulator(target)
    private val renderer = CursorRenderer(context)
    private val scroller = CursorScroller(surface, touchEmulator)
    private val pinchZoom = PinchZoomGesture(surface, target)

    /** The emulator [pointerMode] selects; every synthetic pointer event of the cursor goes through it */
    private var pointer: PointerEmulator = touchEmulator

    /**
     * How the cursor talks to the page, a finger or a mouse. The engine sets it (a mouse for pages
     * in the desktop view). Switching it ends the gesture in flight first.
     */
    var pointerMode: PointerMode = PointerMode.TOUCH
        set(value) {
            if (field == value) return
            // the stream in flight belongs to the old emulator: it is closed before the swap
            cancelActiveGestures()
            field = value
            pointer = if (value == PointerMode.MOUSE) mouseEmulator else touchEmulator
            scroller.pointer = pointer
        }

    private var cursorRadiusAnimationMultiplier: Float = 1f
    /** Pixels/sec cap baseline from display width (see [init]); scaled by [Config.cursorMaxSpeedPercent]. */
    private var maxSpeedBaselinePx: Float = 0f
    private var scrollStartPadding = 100
    /** Where the cursor moves, each component in -1..1; (0, 0) while it stands */
    private val cursorDirection = PointF(0f, 0f)
    val cursorPosition = PointF(0f, 0f)
    /** Pixels per frame along [cursorDirection]: grows with the acceleration up to the cap */
    private var cursorSpeed = 0f
    private var lastCursorUpdate = SystemClock.uptimeMillis() - CURSOR_DISAPPEAR_TIMEOUT
    private var dpadCenterPressed = false
    /**
     * The click key is held but the mouse press is not sent yet (only in [PointerMode.MOUSE]). A mouse
     * cannot cancel a press: its release is a click, and GeckoView keeps the pressed element `:active`
     * after a cancel. So the press waits for the cursor to move (a drag) or the key to go up (a
     * click), and a long press, which opens the menu of the cursor, leaves nothing in the page.
     */
    private var mousePressPending = false
    private val tmpPointF = PointF()
    var callback: Callback? = null
    var customScrollCallback: CustomScrollCallback?
        get() = scroller.customScrollCallback
        set(value) {
            scroller.customScrollCallback = value
        }
    private val cursorHideRunnable = Runnable { surface.invalidate() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var grabMode = false
        set(value) {
            if (field == value) return
            field = value
            callback?.onGrabModeChanged(value)
        }

    //handle long press
    private val longPressRunnable = Runnable {
        // End the synthetic pointer stream before clearing state. Otherwise the page still has an
        // active pointer while dpadCenterPressed is false — scroll-hack or the next frame can inject
        // another ACTION_DOWN, which yields ACTION_CANCEL + ACTION_DOWN and a spurious tap.
        if (dpadCenterPressed) {
            endPress(cancel = true)
            if (pointerMode == PointerMode.MOUSE && target.contextClickOnLongPress) {
                pointer.contextClick(cursorPosition.x, cursorPosition.y)
            }
        }
        surface.keyDispatcherState.reset(this@CursorDrawerDelegate)
        grabMode = false
        callback?.onLongPress(cursorPosition.x.toInt(), cursorPosition.y.toInt())
    }

    /** The click key went down: a finger touches at once, a mouse waits (see [mousePressPending]) */
    private fun startPress() {
        dpadCenterPressed = true
        if (pointerMode == PointerMode.MOUSE) {
            mousePressPending = true
            // A mouse hovers where it presses. The cursor may not have moved since the page loaded,
            // and a long press needs the page to know the element under it well before the menu
            // asks (WebView finds the link of the menu from the last hovered element).
            pointer.move(cursorPosition.x, cursorPosition.y, false)
        } else {
            pointer.press(cursorPosition.x, cursorPosition.y)
        }
    }

    /** Sends the mouse press that waited, at the point where the key went down */
    private fun flushPendingPress(x: Float, y: Float) {
        if (mousePressPending) {
            mousePressPending = false
            pointer.press(x, y)
        }
    }

    /** Releases (a click) or cancels the press; a mouse press that did not start is clicked or dropped */
    private fun endPress(cancel: Boolean) {
        if (!dpadCenterPressed) return
        dpadCenterPressed = false
        if (mousePressPending) {
            mousePressPending = false
            if (!cancel) {
                pointer.press(cursorPosition.x, cursorPosition.y)
                pointer.release(cursorPosition.x, cursorPosition.y)
            }
        } else if (cancel) {
            pointer.cancel(cursorPosition.x, cursorPosition.y)
        } else {
            pointer.release(cursorPosition.x, cursorPosition.y)
        }
    }

    private val isCursorDisappear: Boolean
        get() {
            val newTime = SystemClock.uptimeMillis()
            return newTime - lastCursorUpdate > CURSOR_DISAPPEAR_TIMEOUT
        }

    private fun scheduleCursorHide() {
        mainHandler.removeCallbacks(cursorHideRunnable)
        mainHandler.postDelayed(cursorHideRunnable, CURSOR_DISAPPEAR_TIMEOUT.toLong())
    }

    interface Callback {
        fun onLongPress(x: Int, y: Int)
        fun onGrabModeChanged(grabMode: Boolean) {}
    }

    interface CustomScrollCallback {
        fun onScroll(scrollX: Int, scrollY: Int): Boolean
    }

    fun init() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displaySize = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getSize(displaySize)
        maxSpeedBaselinePx = (displaySize.x / 25).toFloat()
        scrollStartPadding = displaySize.x / 15
    }

    fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cursorPosition.set(w / 2.0f, h / 2.0f)
        scroller.onSizeChanged(w, h)
        scheduleCursorHide()
    }

    /** Shows the cursor at the point of the surface (kept inside it) */
    fun moveTo(x: Float, y: Float) {
        cursorPosition.set(bound(x, 0f, (surface.width - 1).toFloat()), bound(y, 0f, (surface.height - 1).toFloat()))
        if (!dpadCenterPressed) {
            // a mouse hovers the new place, a finger in the air reports nothing
            pointer.move(cursorPosition.x, cursorPosition.y, false)
        }
        animateAppearing()
    }

    /**
     * Ends everything the page still believes is in flight (the press, the scroll hack, the hover
     * of a mouse) and stops the movement. Called when the cursor is turned off or its [pointerMode]
     * changes: from then on the key up events can no longer reach the cursor, so without this the
     * page would keep a stuck pointer. The grab mode is left as it is.
     */
    fun cancelActiveGestures() {
        surface.removeCallbacks(longPressRunnable)
        surface.removeCallbacks(cursorUpdateRunnable)
        endPress(cancel = true)
        scroller.cancelScrollHack()
        pointer.detach()
        cursorDirection.set(0f, 0f)
        cursorSpeed = 0f
        // null while the view is not attached to a window
        surface.keyDispatcherState?.reset(this)
        surface.postInvalidate()
    }

    fun canHandleBackNavigation(): Boolean {
        return grabMode
    }

    fun handleBackNavigation() {
        if (grabMode) {
            exitGrabMode()
        }
    }

    /**
     * Sets where the cursor moves: [x] and [y] in -1..1 (left and up are negative). Keys and the hat
     * give whole steps (a diagonal is (1, 1)), a stick its deflection. The speed grows with the
     * acceleration of the settings up to their cap and is scaled by the vector, so a new vector
     * steers the moving cursor without stopping it; (0, 0) stops it at once. A cursor that is hidden
     * shows again when it starts moving.
     */
    fun setDirection(x: Float, y: Float) {
        val newX = bound(x, 1f)
        val newY = bound(y, 1f)
        val wasMoving = cursorDirection.x != 0f || cursorDirection.y != 0f
        cursorDirection.set(newX, newY)
        if (newX == 0f && newY == 0f) {
            if (wasMoving) {
                // the next frame ends the loop and schedules the hiding
                cursorSpeed = 0f
                scroller.cancelScrollHack()
            }
            return
        }
        if (!wasMoving) {
            // Only a start stamps the time: sticks report many times a frame, and the
            // acceleration of a frame comes from the time since the previous one
            lastCursorUpdate = SystemClock.uptimeMillis()
            surface.removeCallbacks(cursorUpdateRunnable)
            surface.post(cursorUpdateRunnable)
        }
    }

    /** The selection keys (a press of the cursor); directions come through [setDirection] */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                // All selection keys are one press of the cursor; keyDispatcherState tracks each key
                // code apart, so a second one held during a press does not start another.
                if (event.action == KeyEvent.ACTION_DOWN &&
                    event.repeatCount == 0 &&
                    !surface.keyDispatcherState.isTracking(event)
                ) {
                    if (grabMode) {
                        exitGrabMode()
                        return false
                    } else {
                        surface.keyDispatcherState.startTracking(event, this)
                        if (!isCursorDisappear && !dpadCenterPressed) {
                            startPress()
                            surface.postInvalidate()
                            surface.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
                        }
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    surface.keyDispatcherState.handleUpEvent(event)
                    surface.removeCallbacks(longPressRunnable)
                    if (grabMode) {
                        //nop
                    } else if (isCursorDisappear) {
                        lastCursorUpdate = SystemClock.uptimeMillis()
                        surface.postInvalidate()
                        scheduleCursorHide()
                    } else {
                        endPress(cancel = false)
                        surface.postInvalidate()
                    }
                }

                return true
            }
        }
        return false
    }

    private fun bound(value: Float, max: Float): Float {
        return if (value > max) {
            max
        } else if (value < -max) {
            -max
        } else {
            value
        }
    }

    private fun bound(value: Float, min: Float, max: Float): Float {
        return if (value > max) {
            max
        } else if (value < min) {
            min
        } else {
            value
        }
    }

    fun dispatchDraw(canvas: Canvas) {
        if (grabMode || !isCursorDisappear) {
            renderer.draw(canvas, cursorPosition, dpadCenterPressed, grabMode, cursorRadiusAnimationMultiplier)
        }
    }

    fun goToGrabMode() {
        grabMode = true
        surface.postInvalidate()
    }

    fun exitGrabMode() {
        // the grab mode holds no press of its own: this UP is what it has always sent, as a finger
        touchEmulator.release(cursorPosition.x, cursorPosition.y)
        dpadCenterPressed = false
        grabMode = false
        surface.postInvalidate()
    }

    private val cursorUpdateRunnable = object : Runnable {
        override fun run() {
            mainHandler.removeCallbacks(cursorHideRunnable)

            val newTime = SystemClock.uptimeMillis()
            val dTime = newTime - lastCursorUpdate
            lastCursorUpdate = newTime

            if (cursorDirection.x == 0f && cursorDirection.y == 0f) {
                cursorSpeed = 0f
                scroller.cancelScrollHack()
                scheduleCursorHide()
                return
            }
            val cfg = AppContext.provideConfig()
            val speedCap = maxSpeedBaselinePx * (cfg.cursorMaxSpeedPercent / 100f)
            val acceleration = 0.05f * (cfg.cursorAccelerationPercent / 100f) * dTime
            cursorSpeed = (cursorSpeed + acceleration).coerceAtMost(speedCap)
            // each axis reaches the cap together with the speed, as when the axes were apart
            val speedX = cursorDirection.x * cursorSpeed
            val speedY = cursorDirection.y * cursorSpeed
            tmpPointF.set(cursorPosition)
            cursorPosition.offset(speedX, speedY)
            surface.removeCallbacks(longPressRunnable)
            if (cursorPosition.x < 0) {
                cursorPosition.x = 0f
            } else if (cursorPosition.x > surface.width - 1) {
                cursorPosition.x = (surface.width - 1).toFloat()
            }
            if (cursorPosition.y < 0) {
                cursorPosition.y = 0f
            } else if (cursorPosition.y > surface.height - 1) {
                cursorPosition.y = (surface.height - 1).toFloat()
            }
            var dx = 0
            var dy = 0
            if (cursorPosition.y > surface.height - scrollStartPadding) {
                if (speedY > 0) {
                    dy = speedY.toInt()
                }
            } else if (cursorPosition.y < scrollStartPadding) {
                if (speedY < 0) {
                    dy = speedY.toInt()
                }
            }
            if (cursorPosition.x > surface.width - scrollStartPadding) {
                if (speedX > 0) {
                    dx = speedX.toInt()
                }
            } else if (cursorPosition.x < scrollStartPadding) {
                if (speedX < 0) {
                    dx = speedX.toInt()
                }
            }
            if (dx != 0 || dy != 0) {
                // The scroll hack stays off while "click" is held (it would open a second synthetic
                // touch stream on top of the drag). A mouse ignores it: its wheel is no gesture.
                scroller.scrollBy(dx, dy, cursorPosition, allowScrollHack = !dpadCenterPressed)
            }
            // A finger reports a move only while "click" is held (the scroll hack runs its own
            // stream), a mouse hovers the rest of the time
            if (tmpPointF.x != cursorPosition.x || tmpPointF.y != cursorPosition.y) {
                // a drag starts where the key went down
                flushPendingPress(tmpPointF.x, tmpPointF.y)
                pointer.move(cursorPosition.x, cursorPosition.y, dpadCenterPressed)
            }

            surface.invalidate()
            surface.post(this)
        }
    }

    fun tryZoomIn() {
        pinchZoom.start(zoomIn = true)
    }

    fun tryZoomOut() {
        pinchZoom.start(zoomIn = false)
    }

    fun animateAppearing() {
        lastCursorUpdate = SystemClock.uptimeMillis()
        scheduleCursorHide()
        cursorRadiusAnimationMultiplier = 2f
        val animator = ValueAnimator.ofFloat(2f, 1f)
        animator.duration = 300
        animator.addUpdateListener { valueAnimator ->
            cursorRadiusAnimationMultiplier = valueAnimator.animatedValue as Float
            surface.postInvalidate()
        }
        animator.start()
    }

    fun hideCursor() {
        // a press already in the page is released as before, a mouse press that waited is dropped
        endPress(cancel = mousePressPending)
        grabMode = false
        lastCursorUpdate = SystemClock.uptimeMillis() - CURSOR_DISAPPEAR_TIMEOUT
        surface.postInvalidate()
    }

    companion object {
        private const val CURSOR_DISAPPEAR_TIMEOUT = 5000
        //100ms more to let underlying view handle long press first
        //idea to let geckoview handle long press first (and receive ContentDelegate.onContextMenu callback)
        //and if it doesn't handle it, then we handle it as long press
        private val LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout() + 100L
    }
}
