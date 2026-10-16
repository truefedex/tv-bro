package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ViewSitePanelBinding

/**
 * The TV panel of the site of the current page, opened under the site button of the bar: its site
 * settings. Changing ad blocking reloads the page and the focus stays in the panel.
 */
class SitePanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AnchoredOverlayView(context, attrs) {
    private val vb = ViewSitePanelBinding.inflate(LayoutInflater.from(context), this)
    override val panel: View get() = vb.vSiteSettings

    var callback: SiteSettingsView.Callback?
        get() = vb.vSiteSettings.callback
        set(value) {
            vb.vSiteSettings.callback = value
        }

    /** Opens the panel under the site button, with the focus on ad blocking */
    fun show(anchor: View) {
        showUnder(anchor, resources.getDimensionPixelSize(R.dimen.ds_site_panel_gap), vb.vSiteSettings.firstRow)
    }

    fun render(state: CurrentTabState) = vb.vSiteSettings.render(state)
}
