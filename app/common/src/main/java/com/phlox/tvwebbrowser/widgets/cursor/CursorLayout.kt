package com.phlox.tvwebbrowser.widgets.cursor

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import com.phlox.tvwebbrowser.utils.DPADNavigationEventsAdapter


/**
 * Created by PDT on 25.08.2016.
 */
class CursorLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null):
    FrameLayout(context, attrs) {
    var cursorEnabled: Boolean
        get() = !willNotDraw()
        set(value) {
            setWillNotDraw(!value)
        }
    lateinit var cursorDrawerDelegate: CursorDrawerDelegate
    private val inputEventsAdapter = DPADNavigationEventsAdapter(onEmulatedKeyEvent = { keyEvent ->
        cursorDrawerDelegate.dispatchKeyEvent(keyEvent)
    })

    init {
        init()
    }

    private fun init() {
        if (isInEditMode) {
            return
        }
        setWillNotDraw(false)
        cursorDrawerDelegate = CursorDrawerDelegate(context, this)
        cursorDrawerDelegate.init()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (isInEditMode || willNotDraw()) {
            return
        }
        cursorDrawerDelegate.onSizeChanged(w, h, ow, oh)
    }

    override fun setWillNotDraw(willNotDraw: Boolean) {
        inputEventsAdapter.resetState()
        super.setWillNotDraw(willNotDraw)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.d("CursorLayout", "dispatchKeyEvent: $event")

        if (willNotDraw()) return super.dispatchKeyEvent(event)

        if (inputEventsAdapter.dispatchKeyEvent(event)) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        Log.d("CursorLayout", "dispatchGenericMotionEvent: $event")

        if (willNotDraw()) return super.dispatchGenericMotionEvent(event)

        if (inputEventsAdapter.dispatchGenericMotionEvent(event)) {
            return true
        }

        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (isInEditMode || willNotDraw()) {
            return
        }

        cursorDrawerDelegate.dispatchDraw(canvas)
    }
}
