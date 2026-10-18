package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible

/**
 * A panel opened under the view that opened it (its anchor), aligned to its start (or the end of another
 * view). It is a view over the screen, not a window, so keys and Back keep going through the activity
 * ([backCallback] is enabled while it is open), and the focus does not leave the panel while it is open.
 * A click around the panel closes it; the anchor gets the focus back.
 */
abstract class AnchoredOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    /** The panel, a direct child laid out at the top start */
    protected abstract val panel: View
    protected var anchor: View? = null
        private set

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = onBack()
    }

    val isOpen: Boolean get() = isVisible

    init {
        visibility = GONE
        setOnClickListener { hide() }
    }

    /**
     * @param gap between the anchor and the panel, negative to overlap the anchor
     * @param focus the view of the panel that gets the focus
     * @param endAlignTo the panel's end is aligned to the end of this view instead of its start to the
     *   anchor's start; the panel then needs a fixed width
     * @param bottomMargin the least space under the panel: a taller panel is cut there
     */
    protected fun showUnder(anchor: View, gap: Int, focus: View, endAlignTo: View? = null, bottomMargin: Int = 0) {
        this.anchor = anchor
        visibility = VISIBLE
        backCallback.isEnabled = true
        val anchorPosition = IntArray(2).also { anchor.getLocationInWindow(it) }
        val ownPosition = IntArray(2).also { getLocationInWindow(it) }
        panel.layoutParams = (panel.layoutParams as LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = if (endAlignTo != null) {
                val endPosition = IntArray(2).also { endAlignTo.getLocationInWindow(it) }
                endPosition[0] + endAlignTo.width - ownPosition[0] - width
            } else {
                anchorPosition[0] - ownPosition[0]
            }
            topMargin = anchorPosition[1] - ownPosition[1] + anchor.height + gap
            this.bottomMargin = bottomMargin
        }
        focus.requestFocus()
    }

    /** Back closes the panel */
    protected open fun onBack() = hide()

    /** Called when the panel is closed, before the anchor gets the focus back */
    protected open fun onHidden() {}

    fun hide() {
        if (!isOpen) return
        visibility = GONE
        backCallback.isEnabled = false
        onHidden()
        anchor?.requestFocus()
        anchor = null
    }

    /**
     * Keys move the focus only inside the panel: the search does not look at the views under it (a nearer
     * view of the bar would win over the panel's own one)
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!isOpen) return super.focusSearch(focused, direction)
        return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }
}
