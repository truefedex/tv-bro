package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ViewSiteSettingsBinding

/**
 * The settings of the site of the current page: its host, ad blocking (On/Off with the ads blocked on
 * the page) and the popup blocking level (with the pop-ups blocked). The TV site panel and the phone
 * menu sheet show it.
 */
class SiteSettingsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    interface Callback {
        /** Turns ad blocking of the site on or off; the page reloads */
        fun toggleAdBlock()
        /** Opens the popup blocking levels */
        fun choosePopupBlockLevel()
    }

    var callback: Callback? = null
    private val vb = ViewSiteSettingsBinding.inflate(LayoutInflater.from(context), this)

    /** The first row, ad blocking: it gets the focus when the TV panel opens */
    val firstRow: View get() = vb.llAdBlock

    init {
        orientation = VERTICAL
        clipToOutline = true
        vb.llAdBlock.setOnClickListener { callback?.toggleAdBlock() }
        vb.llPopups.setOnClickListener { callback?.choosePopupBlockLevel() }
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
