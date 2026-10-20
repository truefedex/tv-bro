package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DimenRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMarginsRelative
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.siteViewPosition
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.browser.SiteConnection
import com.phlox.tvwebbrowser.databinding.ViewSiteSettingsBinding
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.common.R as CommonR

/**
 * The settings of the site of the current page: its host and the state of its connection, ad blocking
 * (On/Off with the ads blocked on the page), the popup blocking level (with the pop-ups blocked), the site
 * view (desktop or mobile) and, apart at the bottom, Clear site data. Its variant (the `siteSettingsVariant`
 * attribute) says where it is: the TV site panel, the phone site sheet (the same parts in sections, at
 * touch sizes), or the site section of the phone menu sheet (a header that opens the site sheet, rows
 * without icons, no Clear site data).
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
        /** The header of the menu variant: opens the site sheet */
        fun openSiteSettings() {}
    }

    /** In the order of the values of the attribute */
    private enum class Variant { PANEL, SHEET, MENU }

    var callback: Callback? = null
    private val vb = ViewSiteSettingsBinding.inflate(LayoutInflater.from(context), this)
    private val variant = context.obtainStyledAttributes(attrs, R.styleable.SiteSettingsView).run {
        val value = getInt(R.styleable.SiteSettingsView_siteSettingsVariant, 0)
        recycle()
        Variant.entries[value]
    }

    /** The first row, ad blocking: it gets the focus when the TV panel opens */
    val firstRow: View get() = vb.llAdBlock

    private val rows get() = listOf(vb.llAdBlock, vb.llPopups, vb.llSiteView, vb.llClearData)
    private val rowIcons get() = listOf(vb.ivAdBlock, vb.ivPopups, vb.ivSiteView, vb.ivClearData)
    private val rowTexts get() = listOf(vb.llAdBlockText, vb.llPopupsText, vb.llSiteViewText, vb.llClearDataText)
    private val titles get() = listOf(vb.tvAdBlockTitle, vb.tvPopupsTitle, vb.tvSiteViewTitle, vb.tvClearDataTitle)
    private val captions get() = listOf(vb.tvAdBlockCount, vb.tvPopupsCount, vb.tvSiteViewCaption, vb.tvClearDataCaption)
    private val values get() = listOf(vb.tvAdBlockValue, vb.tvPopupsValue, vb.tvSiteViewValue)
    private val chevrons get() = listOf(vb.ivPopupsChevron, vb.ivSiteViewChevron)

    init {
        orientation = VERTICAL
        clipToOutline = true
        vb.llAdBlock.setOnClickListener { callback?.toggleAdBlock() }
        vb.llPopups.setOnClickListener { callback?.choosePopupBlockLevel() }
        vb.llSiteView.setOnClickListener { callback?.chooseSiteView() }
        vb.llClearData.setOnClickListener { callback?.clearSiteData() }
        vb.llMenuHeader.setOnClickListener { callback?.openSiteSettings() }
        when (variant) {
            Variant.PANEL -> {}
            Variant.SHEET -> applySheetLook()
            Variant.MENU -> applyMenuLook()
        }
    }

    fun render(state: CurrentTabState) {
        vb.tvHost.text = state.host
        vb.tvMenuHost.text = state.host
        vb.ivMenuSite.showSiteConnection(state.host, state.connection)
        vb.llMenuHeader.contentDescription = vb.ivMenuSite.contentDescription
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
        val clearData = if (state.siteDataClearable && variant != Variant.MENU) VISIBLE else GONE
        //the site sheet sets it apart in a section of its own instead
        vb.vClearDataSeparator.visibility = if (variant == Variant.PANEL) clearData else GONE
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

    /** The phone site sheet: the header on the sheet, the rows in a section, Clear site data in another */
    private fun applySheetLook() {
        applyTouchRows(R.dimen.ds_phone_site_sheet_row_height, R.dimen.ds_text_phone_site_sheet_title,
            R.dimen.ds_phone_site_sheet_gap)
        rowIcons.forEach { it.setSize(R.dimen.ds_phone_site_sheet_icon) }
        vb.llHeader.setPaddingRelative(px(R.dimen.ds_phone_site_sheet_header_padding), 0,
            px(R.dimen.ds_phone_site_sheet_header_padding), px(R.dimen.ds_phone_sheet_section_gap))
        vb.tvHost.setTextSizePx(R.dimen.ds_text_phone_site_host)
        vb.tvConnection.setTextSizePx(R.dimen.ds_text_phone_site_connection)
        vb.tvConnectionReason.setTextSizePx(R.dimen.ds_text_phone_site_connection)
        vb.ivConnection.setSize(R.dimen.ds_phone_site_connection_icon)
        vb.ivConnection.updateLayoutParams<MarginLayoutParams> {
            marginEnd = px(R.dimen.ds_phone_site_connection_icon_text)
        }
        vb.vHeaderSeparator.visibility = GONE
        for (section in listOf(vb.llRows, vb.llClearData)) {
            section.background = AppCompatResources.getDrawable(context, R.drawable.ds_section_bg)
            section.clipToOutline = true
        }
        vb.llClearData.updateLayoutParams<MarginLayoutParams> { topMargin = px(R.dimen.ds_phone_sheet_section_gap) }
    }

    /** The site section of the phone menu sheet: a header that opens the site sheet, rows without icons */
    private fun applyMenuLook() {
        vb.llHeader.visibility = GONE
        vb.llMenuHeader.visibility = VISIBLE
        applyTouchRows(R.dimen.ds_phone_site_menu_row_height, R.dimen.ds_text_phone_site_menu_title,
            R.dimen.ds_phone_site_menu_gap)
        rowIcons.forEach { it.visibility = GONE }
        rowTexts.forEach { it.updateLayoutParams<MarginLayoutParams> { updateMarginsRelative(start = 0) } }
    }

    private fun applyTouchRows(@DimenRes rowHeight: Int, @DimenRes titleSize: Int, @DimenRes gap: Int) {
        for (row in rows) {
            row.updateLayoutParams { height = px(rowHeight) }
            val end = if (row == vb.llPopups || row == vb.llSiteView) R.dimen.ds_phone_site_row_padding_end else
                R.dimen.ds_phone_site_row_padding_start
            row.setPaddingRelative(px(R.dimen.ds_phone_site_row_padding_start), 0, px(end), 0)
        }
        titles.forEach { it.setTextSizePx(titleSize) }
        captions.forEach { it.setTextSizePx(R.dimen.ds_text_phone_site_caption) }
        values.forEach { it.setTextSizePx(R.dimen.ds_text_phone_site_value) }
        (rowTexts + values).forEach { it.updateLayoutParams<MarginLayoutParams> { updateMarginsRelative(start = px(gap)) } }
        chevrons.forEach {
            it.setSize(R.dimen.ds_phone_site_chevron)
            it.updateLayoutParams<MarginLayoutParams> { updateMarginsRelative(start = px(gap)) }
        }
    }

    private fun px(@DimenRes id: Int) = resources.getDimensionPixelSize(id)

    private fun android.widget.TextView.setTextSizePx(@DimenRes id: Int) =
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(id))

    private fun ImageView.setSize(@DimenRes id: Int) = updateLayoutParams {
        width = px(id)
        height = px(id)
    }
}
