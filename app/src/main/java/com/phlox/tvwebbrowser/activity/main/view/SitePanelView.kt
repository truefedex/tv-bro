package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ViewSitePanelBinding

/**
 * The panel of the site of the current page, opened under the site button of the bar: ad blocking
 * (switched right there; the page reloads and the focus stays in the panel) and the popup blocking level.
 */
class SitePanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AnchoredOverlayView(context, attrs) {
    interface Callback {
        fun toggleAdBlock()
        fun choosePopupBlockLevel()
    }

    var callback: Callback? = null
    private val vb = ViewSitePanelBinding.inflate(LayoutInflater.from(context), this)
    override val panel: View get() = vb.llPanel

    init {
        vb.llPanel.clipToOutline = true
        vb.llAdBlock.setOnClickListener { callback?.toggleAdBlock() }
        vb.llPopups.setOnClickListener { callback?.choosePopupBlockLevel() }
    }

    /** Opens the panel under the site button, with the focus on ad blocking */
    fun show(anchor: View) {
        showUnder(anchor, resources.getDimensionPixelSize(R.dimen.ds_site_panel_gap), vb.llAdBlock)
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
