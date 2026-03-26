package com.phlox.tvwebbrowser.widgets.cursor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.phlox.tvwebbrowser.utils.Utils

class CursorDrawerDelegate(val context: Context, val surface: View) {
    private var cursorRadius: Int = 0
    private var cursorRadiusPressed: Int = 0
    private var cursorRadiusAnimationMultiplier: Float = 1f
    private var maxCursorSpeed: Float = 0f
    private var scrollStartPadding = 100
    private var cursorStrokeWidth: Float = 0f
    private val cursorDirection = Point(0, 0)
    val cursorPosition = PointF(0f, 0f)
    private val cursorSpeed = PointF(0f, 0f)
    private val paint = Paint()
    private var lastCursorUpdate = System.currentTimeMillis() - CURSOR_DISAPPEAR_TIMEOUT
    private var dpadCenterPressed = false
    internal var tmpPointF = PointF()
    var callback: Callback? = null
    var customScrollCallback: CustomScrollCallback? = null
    private val cursorHideRunnable = Runnable { surface.invalidate() }
    private var scrollHackStarted = false
    private val scrollHackCoords = PointF()
    private val scrollHackActiveRect = Rect()
    private var grabMode = false
    private var downTime: Long = 0L

    //handle long press
    private val longPressRunnable = Runnable {
        surface.keyDispatcherState.reset(this@CursorDrawerDelegate)
        dpadCenterPressed = false
        grabMode = false
        callback?.onLongPress(cursorPosition.x.toInt(), cursorPosition.y.toInt())
    }

    private val isCursorDisappear: Boolean
        get() {
            val newTime = System.currentTimeMillis()
            return newTime - lastCursorUpdate > CURSOR_DISAPPEAR_TIMEOUT
        }

    interface Callback {
        fun onLongPress(x: Int, y: Int)
    }

    interface CustomScrollCallback {
        fun onScroll(scrollX: Int, scrollY: Int): Boolean
    }

    fun init() {
        paint.isAntiAlias = true
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val displaySize = Point()
        display.getSize(displaySize)
        cursorStrokeWidth = (displaySize.x / 400).toFloat()
        cursorRadius = displaySize.x / 100
        cursorRadiusPressed = cursorRadius - Utils.D2P(context, 5f).toInt()
        maxCursorSpeed = (displaySize.x / 25).toFloat()
        scrollStartPadding = displaySize.x / 15
    }

    fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cursorPosition.set(w / 2.0f, h / 2.0f)
        scrollHackActiveRect.set(0, 0, surface.width, surface.height)
        scrollHackActiveRect.inset(SCROLL_HACK_PADDING, SCROLL_HACK_PADDING)
        surface.postDelayed(cursorHideRunnable, CURSOR_DISAPPEAR_TIMEOUT.toLong())
    }

    fun canHandleBackNavigation(): Boolean {
        return grabMode
    }

    fun handleBackNavigation() {
        if (grabMode) {
            exitGrabMode()
        }
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, -1, UNCHANGED, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, UNCHANGED, false)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, 1, UNCHANGED, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, UNCHANGED, false)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, UNCHANGED, -1, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, UNCHANGED, 0, false)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, UNCHANGED, 1, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, UNCHANGED, 0, false)
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, -1, -1, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false)
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, 1, -1, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false)
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, -1, 1, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false)
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, 1, 1, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_DOWN && !surface.keyDispatcherState.isTracking(event)) {
                    if (grabMode) {
                        exitGrabMode()
                        return false
                    } else {
                        surface.keyDispatcherState.startTracking(event, this)
                        if (!isCursorDisappear) {
                            dpadCenterPressed = true
                            dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_DOWN)
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
                        lastCursorUpdate = System.currentTimeMillis()
                        surface.postInvalidate()
                    } else {
                        dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_UP)
                        dpadCenterPressed = false
                        surface.postInvalidate()
                    }
                }

                return true
            }
        }
        return false
    }

    private fun dispatchMotionEvent(x: Float, y: Float, action: Int, pointerId: Int = 0) {
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            downTime = SystemClock.uptimeMillis()
        }
        val eventTime = SystemClock.uptimeMillis()
        val properties = arrayOfNulls<MotionEvent.PointerProperties>(1)
        val pp1 = MotionEvent.PointerProperties()
        pp1.id = pointerId
        pp1.toolType = MotionEvent.TOOL_TYPE_FINGER
        properties[0] = pp1
        val pointerCoords = arrayOfNulls<MotionEvent.PointerCoords>(1)
        val pc1 = MotionEvent.PointerCoords()
        pc1.x = x
        pc1.y = y
        pc1.pressure = 1f
        pc1.size = 1f
        pointerCoords[0] = pc1
        val motionEvent = MotionEvent.obtain(downTime, eventTime,
            action, 1, properties,
            pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        surface.dispatchTouchEvent(motionEvent)
    }

    private fun handleDirectionKeyEvent(event: KeyEvent, x: Int, y: Int, keyDown: Boolean) {
        lastCursorUpdate = System.currentTimeMillis()
        if (keyDown) {
            if (surface.keyDispatcherState.isTracking(event)) {
                return
            }
            surface.removeCallbacks(cursorUpdateRunnable)
            surface.post(cursorUpdateRunnable)
            surface.keyDispatcherState.startTracking(event, this)
        } else {
            surface.keyDispatcherState.handleUpEvent(event)
            cursorSpeed.set(0f, 0f)
            if (scrollHackStarted) {
                dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_CANCEL)
                scrollHackStarted = false
            }
        }

        cursorDirection.set(if (x == UNCHANGED) cursorDirection.x else x, if (y == UNCHANGED) cursorDirection.y else y)
    }

    private fun scrollWebViewBy(scrollX: Int, scrollY: Int) {
        if (scrollX == 0 && scrollY == 0) {
            return
        }

        if ((scrollX != 0 && surface.canScrollHorizontally(scrollX)) || (scrollY != 0 && surface.canScrollVertically(scrollY))) {
            surface.scrollTo(surface.scrollX + scrollX, surface.scrollY + scrollY)
        } else if (customScrollCallback != null && customScrollCallback?.onScroll(scrollX, scrollY) == true) {
            return
        } else if (USE_SCROLL_HACK && !dpadCenterPressed) {
            var justStarted = false
            if (!scrollHackStarted) {
                scrollHackCoords.set(
                    bound(cursorPosition.x, scrollHackActiveRect.left.toFloat(), scrollHackActiveRect.right.toFloat()),
                    bound(cursorPosition.y, scrollHackActiveRect.top.toFloat(), scrollHackActiveRect.bottom.toFloat()))
                dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_DOWN)
                scrollHackStarted = true
                justStarted = true
            }
            scrollHackCoords.x -= scrollX
            scrollHackCoords.y -= scrollY
            if (scrollHackCoords.x < scrollHackActiveRect.left || scrollHackCoords.x >= scrollHackActiveRect.right ||
                scrollHackCoords.y < scrollHackActiveRect.top || scrollHackCoords.y >= scrollHackActiveRect.bottom) {
                scrollHackCoords.x += scrollX
                scrollHackCoords.y += scrollY
                dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_CANCEL)
                scrollHackStarted = false
                if (!justStarted) {
                    scrollWebViewBy(scrollX, scrollY)
                }
                return
            }
            dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_MOVE)
        }
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
            val cx = cursorPosition.x
            val cy = cursorPosition.y
            val radius = if (dpadCenterPressed) cursorRadiusPressed else
                    (cursorRadius * cursorRadiusAnimationMultiplier)

            paint.color = when {
                grabMode -> Color.argb(128, 200, 200, 255)
                else -> Color . argb (128, 255, 255, 255)
            }
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius.toFloat(), paint)

            paint.color = Color.GRAY
            paint.strokeWidth = cursorStrokeWidth
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, radius.toFloat(), paint)

            if (grabMode) {
                val halfRadius = radius.toFloat() / 2
                canvas.drawLine(cx - halfRadius, cy, cx + halfRadius, cy, paint)
                canvas.drawLine(cx, cy - halfRadius, cx, cy + halfRadius, paint)
            }
        }
    }

    fun goToGrabMode() {
        grabMode = true
        surface.postInvalidate()
    }

    fun exitGrabMode() {
        dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_UP)
        dpadCenterPressed = false
        grabMode = false
        surface.postInvalidate()
    }

    private val cursorUpdateRunnable = object : Runnable {
        override fun run() {
            surface.removeCallbacks(cursorHideRunnable)

            val newTime = System.currentTimeMillis()
            val dTime = newTime - lastCursorUpdate
            lastCursorUpdate = newTime

            val accelerationFactor = 0.05f * dTime
            //float decelerationFactor = 1 - Math.min(0.5f, 0.005f * dTime);
            cursorSpeed.set(bound(cursorSpeed.x/* * decelerationFactor*/ + bound(cursorDirection.x.toFloat(), 1f) * accelerationFactor, maxCursorSpeed),
                bound(cursorSpeed.y/* * decelerationFactor*/ + bound(cursorDirection.y.toFloat(), 1f) * accelerationFactor, maxCursorSpeed))
            if (Math.abs(cursorSpeed.x) < 0.1f) cursorSpeed.x = 0f
            if (Math.abs(cursorSpeed.y) < 0.1f) cursorSpeed.y = 0f
            if (cursorDirection.x == 0 && cursorDirection.y == 0 && cursorSpeed.x == 0f && cursorSpeed.y == 0f) {
                surface.postDelayed(cursorHideRunnable, CURSOR_DISAPPEAR_TIMEOUT.toLong())
                return
            }
            tmpPointF.set(cursorPosition)
            cursorPosition.offset(cursorSpeed.x, cursorSpeed.y)
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
            if (tmpPointF != cursorPosition) {
                if (dpadCenterPressed) {
                    dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_MOVE)
                } else {
                    dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_HOVER_MOVE);
                }
            }

            var dx = 0
            var dy = 0
            if (cursorPosition.y > surface.height - scrollStartPadding) {
                if (cursorSpeed.y > 0) {
                    dy = cursorSpeed.y.toInt()
                }
            } else if (cursorPosition.y < scrollStartPadding) {
                if (cursorSpeed.y < 0) {
                    dy = cursorSpeed.y.toInt()
                }
            }
            if (cursorPosition.x > surface.width - scrollStartPadding) {
                if (cursorSpeed.x > 0) {
                    dx = cursorSpeed.x.toInt()
                }
            } else if (cursorPosition.x < scrollStartPadding) {
                if (cursorSpeed.x < 0) {
                    dx = cursorSpeed.x.toInt()
                }
            }
            if (dx != 0 || dy != 0) {
                scrollWebViewBy(dx, dy)
            }

            surface.invalidate()
            surface.post(this)
        }
    }

    fun tryZoomIn() {
        generateZoomGesture(true)
    }

    fun tryZoomOut() {
        generateZoomGesture(false)
    }

    fun animateAppearing() {
        lastCursorUpdate = System.currentTimeMillis()
        cursorRadiusAnimationMultiplier = 2f
        val animator = ValueAnimator.ofFloat(2f, 1f)
        animator.duration = 300
        animator.addUpdateListener { valueAnimator ->
            cursorRadiusAnimationMultiplier = valueAnimator.animatedValue as Float
            surface.postInvalidate()
        }
        animator.start()
    }

    //https://stackoverflow.com/questions/11523423/how-to-generate-zoom-pinch-gesture-for-testing-for-android
    var pinchZoomStartTime = 0L
    val pinchZoomDuration = 1000
    var pinchZoomIn = true
    val zoomFactor = 0.1f
    private fun generateZoomGesture(pinchZoomIn: Boolean) {
        if (pinchZoomStartTime != 0L) {
            return
        }
        this.pinchZoomIn = pinchZoomIn
        this.pinchZoomStartTime = System.currentTimeMillis()
        val deltaX = zoomFactor / 2f * surface.height
        val deltaY = zoomFactor / 2f * surface.height
        val deltaX2 = deltaX / 2f
        val deltaY2 = deltaY / 2f
        val startPoint1: PointF = if (pinchZoomIn) {
            PointF(surface.width / 2f - deltaX2, surface.height / 2f - deltaY2)
        } else {
            PointF(surface.width / 2f - deltaX, surface.height / 2f - deltaY)
        }
        val startPoint2: PointF = if (pinchZoomIn) {
            PointF(surface.width / 2f + deltaX2, surface.height / 2f + deltaY2)
        } else {
            PointF(surface.width / 2f + deltaX, surface.height / 2f + deltaY)
        }
        var event: MotionEvent?
        val eventX1: Float = startPoint1.x
        val eventY1: Float = startPoint1.y
        val eventX2: Float = startPoint2.x
        val eventY2: Float = startPoint2.y

        // specify the property for the two touch points
        val properties = arrayOfNulls<MotionEvent.PointerProperties>(2)
        val pp1 = MotionEvent.PointerProperties()
        pp1.id = 0
        pp1.toolType = MotionEvent.TOOL_TYPE_FINGER
        val pp2 = MotionEvent.PointerProperties()
        pp2.id = 1
        pp2.toolType = MotionEvent.TOOL_TYPE_FINGER
        properties[0] = pp1
        properties[1] = pp2

        //specify the coordinations of the two touch points
        //NOTE: you MUST set the pressure and size value, or it doesn't work
        val pointerCoords = arrayOfNulls<MotionEvent.PointerCoords>(2)
        val pc1 = MotionEvent.PointerCoords()
        pc1.x = eventX1
        pc1.y = eventY1
        pc1.pressure = 1f
        pc1.size = 1f
        val pc2 = MotionEvent.PointerCoords()
        pc2.x = eventX2
        pc2.y = eventY2
        pc2.pressure = 1f
        pc2.size = 1f
        pointerCoords[0] = pc1
        pointerCoords[1] = pc2

        //////////////////////////////////////////////////////////////
        // events sequence of zoom gesture
        // 1. send ACTION_DOWN event of one start point
        // 2. send ACTION_POINTER_2_DOWN of two start points
        // 3. send ACTION_MOVE of two middle points
        // 4. repeat step 3 with updated middle points (x,y),
        //      until reach the end points
        // 5. send ACTION_POINTER_2_UP of two end points
        // 6. send ACTION_UP of one end point
        //////////////////////////////////////////////////////////////

        // step 1
        event = MotionEvent.obtain(
            pinchZoomStartTime, pinchZoomStartTime,
            MotionEvent.ACTION_DOWN, 1, properties,
            pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
        )
        surface.dispatchTouchEvent(event)

        //step 2
        event = MotionEvent.obtain(
            pinchZoomStartTime, pinchZoomStartTime,
            MotionEvent.ACTION_POINTER_2_DOWN, 2,
            properties, pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
        )
        surface.dispatchTouchEvent(event)

        surface.post(pinchZoomRunnable)
    }

    fun hideCursor() {
        if (dpadCenterPressed) {
            dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_UP)
            dpadCenterPressed = false
        }
        grabMode = false
        lastCursorUpdate = System.currentTimeMillis() - CURSOR_DISAPPEAR_TIMEOUT
        surface.postInvalidate()
    }

    private val pinchZoomRunnable: Runnable by lazy {
        object : Runnable {
            override fun run() {
                if (pinchZoomStartTime == 0L) {
                    return
                }
                val deltaX = zoomFactor / 2 * surface.height
                val deltaY = zoomFactor / 2 * surface.height
                val deltaX2 = deltaX / 2
                val deltaY2 = deltaY / 2
                val startPoint1: PointF = if (pinchZoomIn) {
                    PointF(surface.width / 2f - deltaX2, surface.height / 2f - deltaY2)
                } else {
                    PointF(surface.width / 2f - deltaX, surface.height / 2f - deltaY)
                }
                val startPoint2: PointF = if (pinchZoomIn) {
                    PointF(surface.width / 2f + deltaX2, surface.height / 2f + deltaY2)
                } else {
                    PointF(surface.width / 2f + deltaX, surface.height / 2f + deltaY)
                }
                val endPoint1: PointF = if (pinchZoomIn) {
                    PointF(surface.width / 2f - deltaX, surface.height / 2f - deltaY)
                } else {
                    PointF(surface.width / 2f - deltaX2, surface.height / 2f - deltaY2)
                }
                val endPoint2: PointF = if (pinchZoomIn) {
                    PointF(surface.width / 2f + deltaX, surface.height / 2f + deltaY)
                } else {
                    PointF(surface.width / 2f + deltaX2, surface.height / 2f + deltaY2)
                }

                val properties = arrayOfNulls<MotionEvent.PointerProperties>(2)
                val pp1 = MotionEvent.PointerProperties()
                pp1.id = 0
                pp1.toolType = MotionEvent.TOOL_TYPE_FINGER
                val pp2 = MotionEvent.PointerProperties()
                pp2.id = 1
                pp2.toolType = MotionEvent.TOOL_TYPE_FINGER
                properties[0] = pp1
                properties[1] = pp2
                val pointerCoords = arrayOfNulls<MotionEvent.PointerCoords>(2)
                val pc1 = MotionEvent.PointerCoords()
                val pc2 = MotionEvent.PointerCoords()
                val now = System.currentTimeMillis()
                if (now - pinchZoomStartTime < pinchZoomDuration) {
                    val progress = (now - pinchZoomStartTime).toFloat() / pinchZoomDuration
                    //step 3, 4
                    // update the move events
                    pc1.x = startPoint1.x + (endPoint1.x - startPoint1.x) * progress
                    pc1.y = startPoint1.y + (endPoint1.y - startPoint1.y) * progress
                    pc2.x = startPoint2.x + (endPoint2.x - startPoint2.x) * progress
                    pc2.y = startPoint2.y + (endPoint2.y - startPoint2.y) * progress
                    pointerCoords[0] = pc1
                    pointerCoords[1] = pc2
                    val event = MotionEvent.obtain(
                        pinchZoomStartTime, now,
                        MotionEvent.ACTION_MOVE, 2, properties,
                        pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
                    )
                    surface.dispatchTouchEvent(event)
                    surface.post(pinchZoomRunnable)
                } else {
                    //step 5
                    pc1.x = endPoint1.x
                    pc1.y = endPoint1.y
                    pc2.x = endPoint2.x
                    pc2.y = endPoint2.y
                    pointerCoords[0] = pc1
                    pointerCoords[1] = pc2
                    var event = MotionEvent.obtain(
                        pinchZoomStartTime, now,
                        MotionEvent.ACTION_POINTER_2_UP, 2, properties,
                        pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
                    )
                    surface.dispatchTouchEvent(event)

                    // step 6
                    event = MotionEvent.obtain(
                        pinchZoomStartTime, now,
                        MotionEvent.ACTION_UP, 1, properties,
                        pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
                    )
                    surface.dispatchTouchEvent(event)
                    pinchZoomStartTime = 0
                }
            }
        }
    }

    companion object {
        private const val UNCHANGED = Integer.MIN_VALUE
        private const val CURSOR_DISAPPEAR_TIMEOUT = 5000
        private const val USE_SCROLL_HACK = true
        private const val SCROLL_HACK_PADDING = 300
        //100ms more to let underlying view handle long press first
        //idea to let geckoview handle long press first (and receive ContentDelegate.onContextMenu callback)
        //and if it doesn't handle it, then we handle it as long press
        private val LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout() + 100L
    }
}