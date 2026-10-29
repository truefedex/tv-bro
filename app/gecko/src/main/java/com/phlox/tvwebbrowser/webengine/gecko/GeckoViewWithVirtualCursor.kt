package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.utils.DPADNavigationEventsAdapter
import com.phlox.tvwebbrowser.utils.dip2px
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import com.phlox.tvwebbrowser.widgets.cursor.PointerTarget
import org.mozilla.geckoview.PanZoomController
import org.mozilla.geckoview.ScreenLength
import java.lang.reflect.Field


class GeckoViewWithVirtualCursor @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null):
    GeckoViewEx(context, attrs), PointerTarget {
    var virtualCursorMode: Boolean = true
        set(value) {
            if (field && !value) {
                // the page must not keep a press or a hover whose end can no longer reach the cursor
                cursorDrawerDelegate.cancelActiveGestures()
            }
            field = value
            dpadNavigationEventsAdapter.resetState()
        }
    lateinit var cursorDrawerDelegate: CursorDrawerDelegate

    private var inputMethodManager: InputMethodManager? = null
    private val dpadNavigationEventsAdapter = DPADNavigationEventsAdapter(
        onEmulatedKeyEvent = { keyEvent ->
            cursorDrawerDelegate.dispatchKeyEvent(keyEvent)
        },
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
        overScrollMode = OVER_SCROLL_NEVER
        inputMethodManager = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager?
        cursorDrawerDelegate = CursorDrawerDelegate(context, this, this)
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

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (virtualCursorMode && DPADNavigationEventsAdapter.isNavigationGenericMotionSource(event.source)) {
            return dpadNavigationEventsAdapter.dispatchGenericMotionEvent(event)
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (virtualCursorMode) {
            if (dpadNavigationEventsAdapter.dispatchKeyEvent(event))
                return true
        }

        return super.dispatchKeyEvent(event)
    }

    /** Called after the mouse of the cursor clicked the page (its primary button went up) */
    var onCursorMouseClick: (() -> Unit)? = null
    private var cursorPrimaryButtonDown = false

    override fun dispatchSyntheticTouch(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) {
            return dispatchTouchEvent(event)
        }
        try {
            return dispatchMouseTouch(event)
        } finally {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> cursorPrimaryButtonDown = (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
                MotionEvent.ACTION_UP -> if (cursorPrimaryButtonDown) {
                    cursorPrimaryButtonDown = false
                    onCursorMouseClick?.invoke()
                }
                MotionEvent.ACTION_CANCEL -> cursorPrimaryButtonDown = false
            }
        }
    }

    private fun dispatchMouseTouch(event: MotionEvent): Boolean {
        // On a device in the television UI mode GeckoView handles the presses of a mouse as touches
        // (PanZoomController.treatMouseAsTouch, a global flag that is also kept for physical mice).
        // The cursor is a mouse only for pages in the desktop view, which expect mouse presses, so
        // the flag is off while GeckoView handles a press of the cursor (synchronously, on this thread).
        val field = treatMouseAsTouchField ?: return dispatchTouchEvent(event)
        val saved = field.get(null)
        field.set(null, false)
        try {
            return dispatchTouchEvent(event)
        } finally {
            field.set(null, saved)
        }
    }

    /** `super`: the hover and wheel events of the cursor go to the page, not to the D-pad adapter */
    override fun dispatchSyntheticGenericMotion(event: MotionEvent): Boolean =
        super.dispatchGenericMotionEvent(event)

    /** GeckoView multiplies the wheel axes by the scroll factor of its PanZoomController */
    override val wheelScrollFactor: Float
        get() = session?.panZoomController?.scrollFactor ?: DEFAULT_GECKO_SCROLL_FACTOR

    /** The long press menu of the cursor gets the link under a mouse from onContextMenu of the right click */
    override val contextClickOnLongPress: Boolean
        get() = true

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (isInEditMode) {
            return
        }

        cursorDrawerDelegate.dispatchDraw(canvas)
    }

    companion object {
        private const val TAG = "GeckoViewWithVirtualCursor"
        /** The default of PanZoomController */
        private const val DEFAULT_GECKO_SCROLL_FACTOR = 64f

        /**
         * `PanZoomController.sTreatMouseAsTouch`: a Boolean computed on first use (null before),
         * null here if this GeckoView version has no such field (presses then stay touches on TV)
         */
        private val treatMouseAsTouchField: Field? by lazy {
            try {
                PanZoomController::class.java.getDeclaredField("sTreatMouseAsTouch").also {
                    it.isAccessible = true
                    require(it.type == Boolean::class.javaObjectType) { "unexpected type ${it.type}" }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No PanZoomController.sTreatMouseAsTouch, mouse presses stay touches on TV", e)
                null
            }
        }
    }
}
