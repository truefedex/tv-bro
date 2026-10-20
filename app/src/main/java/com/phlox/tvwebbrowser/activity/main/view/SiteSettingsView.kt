package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.siteViewPosition
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.browser.SiteConnection
import com.phlox.tvwebbrowser.databinding.ViewSiteSettingsBinding
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.common.R as CommonR

/**
 * The settings of the site of the current page: its host and the state of its connection, ad blocking (On/Off with the ads blocked on
 * the page), the popup blocking level (with the pop-ups blocked) and the site view (desktop or mobile). The TV site panel and the phone
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
        /** Opens the choice of the site view, desktop or mobile; the page reloads */
        fun chooseSiteView()
        /** Asks whether to clear the data of the site; the page reloads */
        fun clearSiteData()
    }

    var callback: Callback? = null
    private val vb = ViewSiteSettingsBinding.inflate(LayoutInflater.from(context), this)
    /** The site section of the phone menu sheet: without Clear site data */
    private val menuVariant = context.obtainStyledAttributes(attrs, R.styleable.SiteSettingsView).run {
        val variant = getInt(R.styleable.SiteSettingsView_siteSettingsVariant, VARIANT_PANEL)
        recycle()
        variant == VARIANT_MENU
    }

    /** The first row, ad blocking: it gets the focus when the TV panel opens */
    val firstRow: View get() = vb.llAdBlock

    init {
        orientation = VERTICAL
        clipToOutline = true
        vb.llAdBlock.setOnClickListener { callback?.toggleAdBlock() }
        vb.llPopups.setOnClickListener { callback?.choosePopupBlockLevel() }
        vb.llSiteView.setOnClickListener { callback?.chooseSiteView() }
        vb.llClearData.setOnClickListener { callback?.clearSiteData() }
    }

    fun render(state: CurrentTabState) {
        vb.tvHost.text = state.host
        renderConnection(state.connection)
        vb.tvAdBlockValue.setText(if (state.adBlockEnabled) R.string.state_on else R.string.state_off)
        vb.tvAdBlockCount.text = resources.getQuantityString(R.plurals.blocked_on_page,
            state.blockedAds, state.blockedAds)
        vb.tvPopupsValue.text = resources.getStringArray(R.array.popup_blocking_level_short)
            .getOrElse(state.popupBlockLevel) { "" }
        vb.tvPopupsCount.text = resources.getQuantityString(R.plurals.blocked_on_page,
            state.blockedPopups, state.blockedPopups)
        val siteView = if (state.siteViewAvailable) VISIBLE else GONE
        vb.vSiteViewSeparator.visibility = siteView
        vb.llSiteView.visibility = siteView
        vb.tvSiteViewValue.text = resources.getStringArray(R.array.site_views)[siteViewPosition(state.desktopView)]
        vb.tvSiteViewCaption.setText(if (state.siteViewIsDefault) R.string.site_view_default else R.string.site_view_of_site)
        val clearData = if (state.siteDataClearable && !menuVariant) VISIBLE else GONE
        vb.vClearDataSeparator.visibility = clearData
        vb.llClearData.visibility = clearData
        //the focus goes through the shown rows only
        val lastAbove = if (state.siteViewAvailable) vb.llSiteView else vb.llPopups
        vb.llPopups.nextFocusDownId = if (state.siteViewAvailable) R.id.llSiteView else R.id.llClearData
        vb.llClearData.nextFocusUpId = lastAbove.id
    }

    /** "Connection is secure" with a lock, or the warning sign in the danger color and the reason */
    private fun renderConnection(connection: SiteConnection) {
        val reason = connection.reason()
        val insecure = reason != null
        vb.ivConnection.setImageResource(if (insecure) R.drawable.ic_warning else R.drawable.ic_lock)
        vb.ivConnection.imageTintList = ColorStateList.valueOf(context.themeColor(
            if (insecure) R.attr.tbColorDsDanger else R.attr.tbColorDsIcon))
        vb.tvConnection.text = context.getString(if (insecure) CommonR.string.connection_isnt_secure else
            R.string.connection_is_secure)
        vb.tvConnection.setTypeface(null, if (insecure) Typeface.BOLD else Typeface.NORMAL)
        vb.tvConnectionReason.visibility = if (insecure) VISIBLE else GONE
        if (reason != null) vb.tvConnectionReason.setText(reason)
    }

    private companion object {
        const val VARIANT_PANEL = 0
        const val VARIANT_MENU = 1
    }
}
