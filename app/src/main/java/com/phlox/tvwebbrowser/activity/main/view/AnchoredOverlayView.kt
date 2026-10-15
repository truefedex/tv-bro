package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible

/**
 * A panel opened under the view that opened it (its anchor), aligned to its start. It is a view over
 * the screen, not a window, so keys and Back keep going through the activity ([backCallback] is
 * enabled while it is open). A click around the panel closes it; the anchor gets the focus back.
 */
abstract class AnchoredOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    /** The panel, a direct child laid out at the top start */
    protected abstract val panel: View
    private var anchor: View? = null

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
    }

    val isOpen: Boolean get() = isVisible

    init {
        visibility = GONE
        setOnClickListener { hide() }
    }

    /**
     * @param gap between the anchor and the panel, negative to overlap the anchor
     * @param focus the view of the panel that gets the focus
     */
    protected fun showUnder(anchor: View, gap: Int, focus: View) {
        this.anchor = anchor
        visibility = VISIBLE
        backCallback.isEnabled = true
        val anchorPosition = IntArray(2).also { anchor.getLocationInWindow(it) }
        val ownPosition = IntArray(2).also { getLocationInWindow(it) }
        panel.layoutParams = (panel.layoutParams as LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = anchorPosition[0] - ownPosition[0]
            topMargin = anchorPosition[1] - ownPosition[1] + anchor.height + gap
        }
        focus.requestFocus()
    }

    fun hide() {
        if (!isOpen) return
        visibility = GONE
        backCallback.isEnabled = false
        anchor?.requestFocus()
        anchor = null
    }
}
