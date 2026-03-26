package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEvent.PointerProperties
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.utils.DPADNavigationEventsAdapter
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.dip2px
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import org.mozilla.geckoview.ScreenLength


class GeckoViewWithVirtualCursor @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null):
    GeckoViewEx(context, attrs) {
    var virtualCursorMode: Boolean = true
        set(value) {
            field = value
            inputEventsAdapter.resetState()
        }
    lateinit var cursorDrawerDelegate: CursorDrawerDelegate

    private var inputMethodManager: InputMethodManager? = null
    private val inputEventsAdapter = DPADNavigationEventsAdapter(
        onEmulatedKeyEvent = { keyEvent ->
            cursorDrawerDelegate.dispatchKeyEvent(keyEvent)
        },
        motionAxesTranslationEnabled = { !AppContext.provideConfig().disableMotionAxesDpadNavigation },
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
        overScrollMode = OVER_SCROLL_NEVER
        inputMethodManager = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager?
        cursorDrawerDelegate = CursorDrawerDelegate(context, this)
        cursorDrawerDelegate.customScrollCallback = object : CursorDrawerDelegate.CustomScrollCallback {
            override fun onScroll(scrollX: Int, scrollY: Int): Boolean {
                return session?.let {
                    it.panZoomController.scrollBy(ScreenLength.fromPixels(scrollX.dip2px(context).toDouble()), ScreenLength.fromPixels(scrollY.dip2px(context).toDouble()))
                    true
                } ?: false
            }
        }
        cursorDrawerDelegate.init()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (isInEditMode) {
            return
        }
        cursorDrawerDelegate.onSizeChanged(w, h, ow, oh)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (virtualCursorMode) {
            return inputEventsAdapter.dispatchGenericMotionEvent(event)
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (virtualCursorMode) {
            return inputEventsAdapter.dispatchKeyEvent(event)
        }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (isInEditMode) {
            return
        }

        cursorDrawerDelegate.dispatchDraw(canvas)
    }
}