package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.*
import androidx.core.view.GestureDetectorCompat
import com.phlox.tvwebbrowser.R

class TitlesView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : View(context, attrs, defStyleAttr) {

    companion object {
        const val ANIMATION_TIME = 250f
    }

    interface Listener {
        fun onTitleChanged(index: Int)
        fun onTitleSelected(index: Int)
        fun onTitleOptions(index: Int)
    }

    var listener: Listener? = null
    var titles = ArrayList<String>()
    var current: Int = 0
        set(value) {
            field = value;
            postInvalidate()
        }
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var gDetector: GestureDetectorCompat
    var animationVector = 0
    var animationStartTime = 0L
    private val emptyTitle: String
    private var onPressTime: Long = 0

    val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
            if (velocityX > 0) moveLeft() else moveRight()
            return true
        }

        override fun onLongPress(e: MotionEvent?) {
            listener?.onTitleOptions(current)
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (e.x < width / 3) {
                moveLeft()
            } else if (e.x > width / 3 * 2) {
                moveRight()
            } else listener?.onTitleSelected(current)
            return true
        }
    }

    init {
        if (isInEditMode) {
            titles.addAll(arrayOf("Test", "Test2", "Test3"))
            current = 0
        }
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        emptyTitle = context.getString(R.string.new_tab_title)

        gDetector = GestureDetectorCompat(context, gestureListener)
    }

    private fun titleForIndex(index: Int): String {
        if (index < 0 || index > (titles.size - 1)) {
            return if (index == -1 || index == titles.size) emptyTitle else ""
        }
        return titles[index]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (titles.isEmpty() || current < -1 || current > titles.size) return
        var text = titleForIndex(current)
        paint.color = if (hasFocus()) Color.BLUE else Color.BLACK
        var animationProgress = (System.currentTimeMillis() - animationStartTime) / ANIMATION_TIME
        if (animationProgress > 1) animationProgress = 1f
        paint.textSize =  when (animationVector) {
            0 -> height / 2f
            else -> animate(height / 3f, height / 2f, animationProgress)
        }
        var xPos = when (animationVector) {
            -1 -> animate(width / 4f, width / 2f, animationProgress)
            1 -> animate(width * 3f / 4f, width / 2f, animationProgress)
            else -> width / 2f
        }
        var yPos = when (animationVector) {
            -1, 1 -> animate(height.toFloat(), height / 2f, animationProgress)
            else -> height / 2f
        }
        text = TextUtils.ellipsize(text, paint, width - width / 4f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, xPos, yPos, paint)

        //left
        text = titleForIndex(current - 1)
        paint.color = when (animationVector) {
            -1 -> Color.argb((animate(0f, 1f, animationProgress) * 255).toInt(), 0, 0, 0)
            else -> Color.BLACK
        }
        paint.textSize = when (animationVector) {
            -1 -> animate(0f, height / 3f, animationProgress)
            1 -> animate(height / 2f, height / 3f, animationProgress)
            else -> height / 3f
        }
        xPos = when (animationVector) {
            -1 -> width / 4f
            1 -> animate(width / 2f, width / 4f, animationProgress)
            else -> width / 4f
        }
        yPos = when (animationVector) {
            -1 -> height.toFloat()
            1 -> animate(height / 2f, height.toFloat(), animationProgress)
            else -> height.toFloat()
        }
        text = TextUtils.ellipsize(text, paint, width / 2f - 10, TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, xPos, yPos, paint)

        //right
        text = titleForIndex(current + 1)
        paint.color = when (animationVector) {
            1 -> Color.argb((animate(0f, 1f, animationProgress) * 255).toInt(), 0, 0, 0)
            else -> Color.BLACK
        }
        paint.textSize = when (animationVector) {
            1 -> animate(0f, height / 3f, animationProgress)
            -1 -> animate(height / 2f, height / 3f, animationProgress)
            else -> height / 3f
        }
        xPos = when (animationVector) {
            1 -> width * 3f / 4f
            -1 -> animate(width / 2f, width * 3f / 4f, animationProgress)
            else -> width * 3f / 4f
        }
        yPos = when (animationVector) {
            1 -> height.toFloat()
            -1 -> animate(height / 2f, height.toFloat(), animationProgress)
            else -> height.toFloat()
        }
        text = TextUtils.ellipsize(text, paint, width / 2f - 10, TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, xPos, yPos, paint)

        //tail (four element visible only while animation)
        if (animationVector != 0) {
            val leftTail = animationVector > 0
            text = if (leftTail) titleForIndex(current - 2) else
                titleForIndex(current + 2)
            paint.color = Color.argb((animate(1f, 0f, animationProgress) * 255).toInt(), 0, 0, 0)
            paint.textSize = animate(height / 3f, 0f, animationProgress)
            xPos = if (leftTail) width / 4f else width * 3f / 4f
            yPos = height.toFloat()
            text = TextUtils.ellipsize(text, paint, width / 2f - 10, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, xPos, yPos, paint)
        }

        if (animationVector != 0) {
            if (animationProgress >= 1f) {
                animationVector = 0
                listener?.onTitleChanged(current)
            } else {
                postInvalidate()
            }
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        postInvalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0) {
                    onPressTime = System.currentTimeMillis()
                } else if ((System.currentTimeMillis() - onPressTime) >= ViewConfiguration.getLongPressTimeout()) {
                    listener?.onTitleOptions(current)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.repeatCount == 0) {
                    onPressTime = System.currentTimeMillis()
                } else {
                    moveLeft()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.repeatCount == 0) {
                    onPressTime = System.currentTimeMillis()
                } else {
                    moveRight()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> return moveLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT -> return moveRight()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if ((System.currentTimeMillis() - onPressTime) < ViewConfiguration.getLongPressTimeout())
                    listener?.onTitleSelected(current)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (gDetector.onTouchEvent(event)) {
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    private fun animate(from: Float, to : Float, animationProgress: Float): Float {
        return from + (to - from) * animationProgress
    }

    private fun moveLeft(): Boolean {
        if (animationVector != 0) return false
        if (current <= -1) return false
        current--
        animationVector = -1
        animationStartTime = System.currentTimeMillis()
        postInvalidate()
        return true
    }

    private fun moveRight(): Boolean {
        if (animationVector != 0) return false
        if (current >= titles.size) return false
        current++
        animationVector = 1
        animationStartTime = System.currentTimeMillis()
        postInvalidate()
        return true
    }
}