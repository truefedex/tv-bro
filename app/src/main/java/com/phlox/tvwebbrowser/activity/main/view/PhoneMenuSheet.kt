package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ViewPhoneMenuSheetBinding

/**
 * The menu of the phone UI, a sheet from the bottom over a scrim. It holds what the TV bar has and the
 * phone bar does not: quick buttons (Forward, New tab, Incognito, Bookmark), the settings of the current
 * site (they keep the sheet open), then History, Bookmarks, Downloads and Settings.
 * A tap on the scrim or Back ([backCallback]) closes it.
 */
class PhoneMenuSheet @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    interface Callback {
        fun goForward()
        fun openNewTab()
        fun toggleIncognitoMode()
        fun addBookmark()
        fun showHistory()
        fun showBookmarks()
        fun showDownloads()
        fun showSettings()
    }

    var callback: Callback? = null
    private val vb = ViewPhoneMenuSheetBinding.inflate(LayoutInflater.from(context), this)
    private var canGoForward = false
    private val sheetPaddingBottom = resources.getDimensionPixelSize(R.dimen.ds_phone_sheet_padding_bottom)

    /** The site section of the sheet */
    val siteSettings: SiteSettingsView get() = vb.vSiteSettings

    /** Enabled while the sheet is open */
    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
    }

    val isOpen: Boolean get() = isVisible

    init {
        visibility = GONE
        vb.vScrim.setOnClickListener { hide() }
        vb.llIncognito.isActivated = AppContext.provideConfig().incognitoMode
        onRow(vb.llForward) { if (canGoForward) callback?.goForward() }
        onRow(vb.llNewTab) { callback?.openNewTab() }
        onRow(vb.llIncognito) { callback?.toggleIncognitoMode() }
        onRow(vb.llBookmark) { callback?.addBookmark() }
        onRow(vb.llHistory) { callback?.showHistory() }
        onRow(vb.llBookmarks) { callback?.showBookmarks() }
        onRow(vb.llDownloads) { callback?.showDownloads() }
        onRow(vb.llSettings) { callback?.showSettings() }
    }

    /** These rows close the sheet first */
    private fun onRow(row: View, action: () -> Unit) {
        row.setOnClickListener {
            hide()
            action()
        }
    }

    /** The sheet keeps out of the navigation bar; the scrim covers it */
    fun setInsets(left: Int, right: Int, bottom: Int) {
        vb.svSheet.setPadding(left, 0, right, 0)
        vb.llSheet.setPadding(vb.llSheet.paddingLeft, vb.llSheet.paddingTop, vb.llSheet.paddingRight,
            sheetPaddingBottom + bottom)
    }

    fun render(state: CurrentTabState) {
        canGoForward = state.canGoForward
        //disabled icons and labels are drawn at 40%
        vb.llForward.alpha = if (state.canGoForward) 1f else DISABLED_ALPHA
        vb.vSiteSettings.visibility = if (state.host.isEmpty()) GONE else VISIBLE
        vb.vSiteSettings.render(state)
    }

    fun show() {
        //it may be closing
        if (backCallback.isEnabled) return
        vb.vScrim.animate().cancel()
        vb.svSheet.animate().cancel()
        visibility = VISIBLE
        backCallback.isEnabled = true
        vb.vScrim.alpha = 0f
        vb.vScrim.animate().alpha(1f).setDuration(ANIMATION_DURATION).start()
        vb.svSheet.scrollTo(0, 0)
        vb.svSheet.post {
            vb.svSheet.translationY = vb.svSheet.height.toFloat()
            vb.svSheet.animate().translationY(0f).setDuration(ANIMATION_DURATION)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun hide() {
        if (!isOpen || !backCallback.isEnabled) return
        backCallback.isEnabled = false
        vb.vScrim.animate().alpha(0f).setDuration(ANIMATION_DURATION).start()
        vb.svSheet.animate().translationY(vb.svSheet.height.toFloat()).setDuration(ANIMATION_DURATION)
            .withEndAction { visibility = GONE }.start()
    }

    companion object {
        private const val ANIMATION_DURATION = 200L
        private const val DISABLED_ALPHA = 0.4f
    }
}
