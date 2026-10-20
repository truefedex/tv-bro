package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ViewPhoneBarBinding

/**
 * The bar of the phone UI, at the bottom: Back, the address pill (the site button with the count of
 * blocked ads and pop-ups, the host only, Refresh or Stop), the tab count and the menu. A tap on the
 * pill edits the full address; the bar stays above the keyboard.
 */
class PhoneBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    interface Callback {
        fun navigateBack()
        /** Stops the page if it is loading, reloads it otherwise */
        fun reloadOrStop()
        fun showSiteSettings()
        fun showTabs()
        fun showMenu()
        /** Navigates to the url or searches the text */
        fun search(text: String)
        /** The address is edited or not any more (Back ends the editing) */
        fun onEditingChanged(editing: Boolean)
    }

    var callback: Callback? = null
    private val vb = ViewPhoneBarBinding.inflate(LayoutInflater.from(context), this)
    /** The url of the page, edited when the pill is tapped */
    private var url = ""
    private var homePage = true
    var editing = false
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.ds_phone_bar_bg)
        val paddingVertical = resources.getDimensionPixelSize(R.dimen.ds_phone_bar_padding_vertical)
        val paddingHorizontal = resources.getDimensionPixelSize(R.dimen.ds_phone_bar_padding_horizontal)
        setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)

        if (!isInEditMode) {
            vb.ibBack.setOnClickListener { callback?.navigateBack() }
            vb.ibRefresh.setOnClickListener { callback?.reloadOrStop() }
            vb.ibSite.setOnClickListener { callback?.showSiteSettings() }
            vb.flTabs.setOnClickListener { callback?.showTabs() }
            vb.ibMenu.setOnClickListener { callback?.showMenu() }
            vb.llPage.setOnClickListener { startEditing() }
            vb.llSearchPrompt.setOnClickListener { startEditing() }
            vb.etUrl.setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    val text = vb.etUrl.text.toString()
                    stopEditing()
                    if (text.isNotBlank()) callback?.search(text)
                    true
                } else false
            }
            vb.etUrl.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) stopEditing()
            }
        }
    }

    fun render(state: CurrentTabState) {
        url = state.url
        homePage = isHomePage(state.url)
        vb.tvHost.text = state.host.removePrefix("www.").ifEmpty { state.url }
        vb.ibSite.showSiteConnection(state.host, state.connection)
        //disabled icons are drawn at 40%
        vb.ibBack.imageAlpha = if (state.canGoBack) 255 else 102
        vb.ibRefresh.setImageResource(if (state.loading) R.drawable.ic_stop else R.drawable.ic_refresh)
        vb.ibRefresh.contentDescription = context.getString(if (state.loading) R.string.stop_loading else R.string.refresh_page)
        updatePill()
    }

    fun setTabCount(count: Int) {
        vb.tvTabCount.text = count.toString()
        vb.flTabs.contentDescription = resources.getQuantityString(R.plurals.tab_count, count, count)
    }

    private fun updatePill() {
        vb.flPill.isActivated = editing
        vb.etUrl.visibility = if (editing) VISIBLE else GONE
        vb.llPage.visibility = if (!editing && !homePage) VISIBLE else GONE
        vb.llSearchPrompt.visibility = if (!editing && homePage) VISIBLE else GONE
    }

    /** The full address (nothing on the home page), selected, with the keyboard */
    fun startEditing() {
        if (editing) return
        editing = true
        vb.etUrl.setText(if (homePage) "" else url)
        updatePill()
        vb.etUrl.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(vb.etUrl, InputMethodManager.SHOW_IMPLICIT)
        callback?.onEditingChanged(true)
    }

    fun stopEditing() {
        if (!editing) return
        editing = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
        if (vb.etUrl.hasFocus()) vb.etUrl.clearFocus()
        updatePill()
        callback?.onEditingChanged(false)
    }

    private fun isHomePage(url: String) =
        url.isEmpty() || url == Config.HOME_URL_ALIAS || url.startsWith(Config.HOME_PAGE_URL)

    /** The site button: the site settings open from it */
    val siteButton: View get() = vb.ibSite
}
