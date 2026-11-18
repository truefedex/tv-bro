package com.phlox.tvwebbrowser.widgets.cursor

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.ViewConfigurationCompat
import androidx.core.view.WindowInsetsCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.utils.DPADNavigationEventsAdapter


/**
 * Created by PDT on 25.08.2016.
 */
class CursorLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null):
    FrameLayout(context, attrs), PointerTarget {
    var cursorEnabled: Boolean
        get() = !willNotDraw()
        set(value) {
            if (value == cursorEnabled) return
            // the page must not keep a press or a hover whose end can no longer reach the cursor
            if (!value) cursorDrawerDelegate.cancelActiveGestures()
            setWillNotDraw(!value)
        }
    lateinit var cursorDrawerDelegate: CursorDrawerDelegate
    private val inputEventsAdapter = DPADNavigationEventsAdapter(
        onDirection = { x, y -> cursorDrawerDelegate.setDirection(x, y) },
        onKeyEvent = { keyEvent -> cursorDrawerDelegate.dispatchKeyEvent(keyEvent) },
        analogStickNavigationEnabled = { !AppContext.provideConfig().disableMotionAxesDpadNavigation },
        isSoftwareKeyboardVisible = {
            ViewCompat.getRootWindowInsets(rootView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        },
    )

    init {
        init()
    }

    private fun init() {
        if (isInEditMode) {
            return
        }
        setWillNotDraw(false)
        cursorDrawerDelegate = CursorDrawerDelegate(context, this, this)
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

    override fun dispatchSyntheticTouch(event: MotionEvent): Boolean =
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) dispatchMouseToChild(event, touch = true)
        else dispatchTouchEvent(event)

    /** Not through the D-pad adapter: the hover and wheel events of the cursor go to the page */
    override fun dispatchSyntheticGenericMotion(event: MotionEvent): Boolean =
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) dispatchMouseToChild(event, touch = false)
        else super.dispatchGenericMotionEvent(event)

    /** The child the mouse of the cursor hovers, which got its hover enter */
    private var hoveredChild: View? = null

    /**
     * A mouse event of the cursor goes straight to the child under its point. A ViewGroup finds that child by
     * the cursor position of a mouse event (Android 14+), which an event an app makes does not have right: on a
     * display with an overridden size (`wm size`, a 4K panel shown at 1080p) the right half of the page got no
     * clicks, wheel or hover. The hover enter and exit the ViewGroup would add are added here.
     */
    private fun dispatchMouseToChild(event: MotionEvent, touch: Boolean): Boolean {
        val child = childUnder(event.x, event.y)
        val action = event.actionMasked
        val hovered = hoveredChild
        if (!touch && hovered != null && (hovered !== child || action == MotionEvent.ACTION_HOVER_EXIT)) {
            hoveredChild = null
            dispatchToChild(hovered, event, MotionEvent.ACTION_HOVER_EXIT, touch = false)
            if (action == MotionEvent.ACTION_HOVER_EXIT) return true
        }
        if (child == null) return false
        if (!touch && action == MotionEvent.ACTION_HOVER_MOVE && hoveredChild == null) {
            hoveredChild = child
            dispatchToChild(child, event, MotionEvent.ACTION_HOVER_ENTER, touch = false)
        }
        if (action == MotionEvent.ACTION_HOVER_EXIT) return false
        return dispatchToChild(child, event, null, touch)
    }

    private fun childUnder(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != VISIBLE) continue
            val childX = x + scrollX - child.left
            val childY = y + scrollY - child.top
            if (childX >= 0 && childY >= 0 && childX < child.width && childY < child.height) return child
        }
        return null
    }

    private fun dispatchToChild(child: View, event: MotionEvent, action: Int?, touch: Boolean): Boolean {
        val copy = MotionEvent.obtain(event)
        try {
            if (action != null) copy.action = action
            copy.offsetLocation((scrollX - child.left).toFloat(), (scrollY - child.top).toFloat())
            return if (touch) child.dispatchTouchEvent(copy) else child.dispatchGenericMotionEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    /** Set by the engine, which knows how its page takes a mouse press */
    override var mouseClicks: MouseClicks = MouseClicks.BUTTON_STATE

    /** WebView scrolls by the scroll factor of the system for one wheel click */
    override val wheelScrollFactor: Float
        get() = ViewConfigurationCompat.getScaledVerticalScrollFactor(ViewConfiguration.get(context), context)

    /** The page is drawn without the cursor (a picture of the screen is taken), see [drawsCursor] */
    var cursorHidden = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The cursor is on the screen now */
    val drawsCursor: Boolean
        get() = !isInEditMode && !willNotDraw() && !cursorHidden && cursorDrawerDelegate.drawsCursor

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (isInEditMode || willNotDraw() || cursorHidden) {
            return
        }

        cursorDrawerDelegate.dispatchDraw(canvas)
    }
}
