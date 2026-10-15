package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ViewSitePanelBinding

/**
 * The panel of the site of the current page, opened under the site button of the bar: ad blocking
 * (switched right there; the page reloads and the focus stays in the panel) and the popup blocking level.
 * A view over the screen, not a window, so keys and Back keep going through the activity.
 */
class SitePanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    interface Callback {
        fun toggleAdBlock()
        fun choosePopupBlockLevel()
    }

    var callback: Callback? = null
    private val vb = ViewSitePanelBinding.inflate(LayoutInflater.from(context), this)
    /** The view that opened the panel; it gets the focus back */
    private var anchor: View? = null

    /** Enabled while the panel is open */
    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
    }

    val isOpen: Boolean get() = isVisible

    init {
        visibility = GONE
        //a click around the panel closes it
        setOnClickListener { hide() }
        vb.llPanel.clipToOutline = true
        vb.llAdBlock.setOnClickListener { callback?.toggleAdBlock() }
        vb.llPopups.setOnClickListener { callback?.choosePopupBlockLevel() }
    }

    /** Opens the panel under the [anchor], aligned to its start, with the focus on ad blocking */
    fun show(anchor: View) {
        this.anchor = anchor
        visibility = VISIBLE
        backCallback.isEnabled = true
        val anchorPosition = IntArray(2).also { anchor.getLocationInWindow(it) }
        val ownPosition = IntArray(2).also { getLocationInWindow(it) }
        vb.llPanel.layoutParams = (vb.llPanel.layoutParams as LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = anchorPosition[0] - ownPosition[0]
            topMargin = anchorPosition[1] - ownPosition[1] + anchor.height +
                    resources.getDimensionPixelSize(R.dimen.ds_site_panel_gap)
        }
        vb.llAdBlock.requestFocus()
    }

    fun hide() {
        if (!isOpen) return
        visibility = GONE
        backCallback.isEnabled = false
        anchor?.requestFocus()
        anchor = null
    }

    fun render(state: CurrentTabState) {
        vb.tvHost.text = state.host
        vb.tvAdBlockValue.setText(if (state.adBlockEnabled) R.string.state_on else R.string.state_off)
        vb.tvAdBlockCount.text = resources.getQuantityString(R.plurals.blocked_on_page,
            state.blockedAds, state.blockedAds)
        vb.tvPopupsValue.text = resources.getStringArray(R.array.popup_blocking_level_short)
            .getOrElse(state.popupBlockLevel) { "" }
        vb.tvPopupsCount.text = resources.getQuantityString(R.plurals.blocked_on_page,
            state.blockedPopups, state.blockedPopups)
    }
}
