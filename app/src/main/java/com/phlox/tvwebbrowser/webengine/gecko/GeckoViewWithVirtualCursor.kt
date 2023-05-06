package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEvent.PointerProperties
import android.view.WindowManager
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.dip2px
import org.mozilla.geckoview.ScreenLength


class GeckoViewWithVirtualCursor @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null):
    GeckoViewEx(context, attrs) {
    companion object {
        private const val UNCHANGED = Integer.MIN_VALUE
        private const val CURSOR_DISAPPEAR_TIMEOUT = 5000
        private const val USE_SCROLL_HACK = true
        private const val SCROLL_HACK_PADDING = 300
    }

    private var cursorRadius: Int = 0
    private var cursorRadiusPressed: Int = 0
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
    private var callback: Callback? = null
    private val cursorHideRunnable = Runnable { invalidate() }
    private var scrollHackStarted = false
    private val scrollHackCoords = PointF()
    private val scrollHackActiveRect = Rect()
    var fingerMode = false
    private var downTime: Long = 0

    private val isCursorDissappear: Boolean
        get() {
            val newTime = System.currentTimeMillis()
            return newTime - lastCursorUpdate > CURSOR_DISAPPEAR_TIMEOUT
        }

    interface Callback {
        fun onUserInteraction()
    }

    init {
        init()
    }

    private fun init() {
        if (isInEditMode) {
            return
        }
        paint.isAntiAlias = true
        setWillNotDraw(false)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val displaySize = Point()
        display.getSize(displaySize)
        cursorStrokeWidth = (displaySize.x / 400).toFloat()
        cursorRadius = displaySize.x / 110
        cursorRadiusPressed = cursorRadius + Utils.D2P(context, 5f).toInt()
        maxCursorSpeed = (displaySize.x / 25).toFloat()
        scrollStartPadding = displaySize.x / 15
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        callback?.onUserInteraction()
        return super.onInterceptTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (isInEditMode) {
            return
        }
        cursorPosition.set(w / 2.0f, h / 2.0f)
        scrollHackActiveRect.set(0, 0, width, height)
        scrollHackActiveRect.inset(SCROLL_HACK_PADDING, SCROLL_HACK_PADDING)
        postDelayed(cursorHideRunnable, CURSOR_DISAPPEAR_TIMEOUT.toLong())
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        callback?.onUserInteraction()
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
                if (event.action == KeyEvent.ACTION_DOWN && !keyDispatcherState.isTracking(event)) {
                    if (fingerMode) {
                        exitFingerMode()
                        return false
                    } else {
                        keyDispatcherState.startTracking(event, this)
                        if (!isCursorDissappear) {
                            dpadCenterPressed = true
                            dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_DOWN)
                            postInvalidate()
                        }
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    keyDispatcherState.handleUpEvent(event)
                    //loadUrl("javascript:function simulateClick(x,y){var clickEvent=document.createEvent('MouseEvents');clickEvent.initMouseEvent('click',true,true,window,0,0,0,x,y,false,false,false,false,0,null);document.elementFromPoint(x,y).dispatchEvent(clickEvent)}simulateClick("+(int)cursorPosition.x+","+(int)cursorPosition.y+");");
                    // Obtain MotionEvent object
                    if (fingerMode) {
                        //nop
                    } else if (isCursorDissappear) {
                        lastCursorUpdate = System.currentTimeMillis()
                        postInvalidate()
                    } else {
                        dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_UP)
                        dpadCenterPressed = false
                        postInvalidate()
                    }
                }

                return true
            }
        }

        val child = getChildAt(0)
        return child?.dispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)
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
        dispatchTouchEvent(motionEvent)
    }

    private fun handleDirectionKeyEvent(event: KeyEvent, x: Int, y: Int, keyDown: Boolean) {
        lastCursorUpdate = System.currentTimeMillis()
        if (keyDown) {
            if (keyDispatcherState.isTracking(event)) {
                return
            }
            removeCallbacks(cursorUpdateRunnable)
            post(cursorUpdateRunnable)
            keyDispatcherState.startTracking(event, this)
        } else {
            keyDispatcherState.handleUpEvent(event)
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
        val session = session
        if ((scrollX != 0 && canScrollHorizontally(scrollX)) || (scrollY != 0 && canScrollVertically(scrollY))) {
            scrollTo(this.scrollX + scrollX, this.scrollY + scrollY)
        } else if (session != null) {
            session.panZoomController.scrollBy(ScreenLength.fromPixels(scrollX.dip2px(context).toDouble()), ScreenLength.fromPixels(scrollY.dip2px(context).toDouble()))
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

    @Suppress("NAME_SHADOWING")
    override fun dispatchDraw(canvas: Canvas?) {
        super.dispatchDraw(canvas)
        if (isInEditMode) {
            return
        }

        val canvas = canvas ?: return

        if (fingerMode || !isCursorDissappear) {
            val cx = cursorPosition.x
            val cy = cursorPosition.y
            val radius = if (dpadCenterPressed) cursorRadiusPressed else cursorRadius

            paint.color = if (fingerMode)
                Color.argb(128, 200, 200, 255) else
                Color.argb(128, 255, 255, 255)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius.toFloat(), paint)

            paint.color = Color.GRAY
            paint.strokeWidth = cursorStrokeWidth
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, radius.toFloat(), paint)

            if (fingerMode) {
                val halfRadius = radius.toFloat() / 2
                canvas.drawLine(cx - halfRadius, cy, cx + halfRadius, cy, paint)
                canvas.drawLine(cx, cy - halfRadius, cx, cy + halfRadius, paint)
            }
        }
    }

    fun goToFingerMode() {
        fingerMode = true
        postInvalidate()
    }

    fun exitFingerMode() {
        dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_UP)
        dpadCenterPressed = false
        fingerMode = false
        postInvalidate()
    }

    private val cursorUpdateRunnable = object : Runnable {
        override fun run() {
            removeCallbacks(cursorHideRunnable)

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
                postDelayed(cursorHideRunnable, CURSOR_DISAPPEAR_TIMEOUT.toLong())
                return
            }
            tmpPointF.set(cursorPosition)
            cursorPosition.offset(cursorSpeed.x, cursorSpeed.y)
            if (cursorPosition.x < 0) {
                cursorPosition.x = 0f
            } else if (cursorPosition.x > width - 1) {
                cursorPosition.x = (width - 1).toFloat()
            }
            if (cursorPosition.y < 0) {
                cursorPosition.y = 0f
            } else if (cursorPosition.y > height - 1) {
                cursorPosition.y = (height - 1).toFloat()
            }
            if (tmpPointF != cursorPosition) {
                if (dpadCenterPressed) {
                    dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_MOVE)
                } else {
                    //dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_HOVER_MOVE);
                }
            }

            var dx = 0
            var dy = 0
            if (cursorPosition.y > height - scrollStartPadding) {
                if (cursorSpeed.y > 0) {
                    dy = cursorSpeed.y.toInt()
                }
            } else if (cursorPosition.y < scrollStartPadding) {
                if (cursorSpeed.y < 0) {
                    dy = cursorSpeed.y.toInt()
                }
            }
            if (cursorPosition.x > width - scrollStartPadding) {
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

            invalidate()
            post(this)
        }
    }

    fun tryZoomIn() {
        generateZoomGesture(true)
    }

    fun tryZoomOut() {
        generateZoomGesture(false)
    }

    //https://stackoverflow.com/questions/11523423/how-to-generate-zoom-pinch-gesture-for-testing-for-android
    var pinchZoomStartTime = 0L
    val pinchZoomDuration = 2000
    var pinchZoomIn = true
    val zoomFactor = 0.01f
    private fun generateZoomGesture(pinchZoomIn: Boolean) {
        if (pinchZoomStartTime != 0L) {
            return
        }
        this.pinchZoomIn = pinchZoomIn
        this.pinchZoomStartTime = System.currentTimeMillis()
        val deltaX = zoomFactor / 2f * width
        val deltaY = zoomFactor / 2f * height
        val startPoint1: PointF = if (pinchZoomIn) {
            PointF(width / 2f, height / 2f)
        } else {
            PointF(width / 2f - deltaX, height / 2f - deltaY)
        }
        val startPoint2: PointF = if (pinchZoomIn) {
            PointF(width / 2f, height / 2f)
        } else {
            PointF(width / 2f + deltaX, height / 2f + deltaY)
        }
        var event: MotionEvent?
        val eventX1: Float = startPoint1.x
        val eventY1: Float = startPoint1.y
        val eventX2: Float = startPoint2.x
        val eventY2: Float = startPoint2.y

        // specify the property for the two touch points
        val properties = arrayOfNulls<PointerProperties>(2)
        val pp1 = PointerProperties()
        pp1.id = 0
        pp1.toolType = MotionEvent.TOOL_TYPE_FINGER
        val pp2 = PointerProperties()
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
        dispatchTouchEvent(event)

        //step 2
        event = MotionEvent.obtain(
            pinchZoomStartTime, pinchZoomStartTime,
            MotionEvent.ACTION_POINTER_2_DOWN, 2,
            properties, pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
        )
        dispatchTouchEvent(event)

        post(pinchZoomRunnable)
    }

    private val pinchZoomRunnable: Runnable by lazy {
        object : Runnable {
            override fun run() {
                if (pinchZoomStartTime == 0L) {
                    return
                }
                val deltaX = zoomFactor / 2 * width
                val deltaY = zoomFactor / 2 * height
                val startPoint1: PointF = if (pinchZoomIn) {
                    PointF(width / 2f, height / 2f)
                } else {
                    PointF(width / 2f - deltaX, height / 2f - deltaY)
                }
                val startPoint2: PointF = if (pinchZoomIn) {
                    PointF(width / 2f, height / 2f)
                } else {
                    PointF(width / 2f + deltaX, height / 2f + deltaY)
                }
                val endPoint1: PointF = if (pinchZoomIn) {
                    PointF(width / 2f + deltaX, height / 2f + deltaY)
                } else {
                    PointF(width / 2f, height / 2f)
                }
                val endPoint2: PointF = if (pinchZoomIn) {
                    PointF(width / 2f - deltaX, height / 2f - deltaY)
                } else {
                    PointF(width / 2f, height / 2f)
                }

                val properties = arrayOfNulls<PointerProperties>(2)
                val pp1 = PointerProperties()
                pp1.id = 0
                pp1.toolType = MotionEvent.TOOL_TYPE_FINGER
                val pp2 = PointerProperties()
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
                    dispatchTouchEvent(event)
                    postDelayed(pinchZoomRunnable, 10)
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
                    dispatchTouchEvent(event)

                    // step 6
                    event = MotionEvent.obtain(
                        pinchZoomStartTime, now,
                        MotionEvent.ACTION_UP, 1, properties,
                        pointerCoords, 0, 0, 1f, 1f, 0, 0, 0, 0
                    )
                    dispatchTouchEvent(event)
                    pinchZoomStartTime = 0
                }
            }
        }
    }
}