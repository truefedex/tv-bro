package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ViewPhoneSiteSheetBinding

/**
 * The site sheet of the phone UI, from the bottom over a scrim: the same settings of the current site as
 * the TV site panel, Clear site data included. The site button of the bar and the site header of the menu
 * sheet open it. Its rows keep it open (the page reloads under it); a tap on the scrim or Back
 * ([backCallback]) closes it.
 */
class PhoneSiteSheet @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val vb = ViewPhoneSiteSheetBinding.inflate(LayoutInflater.from(context), this)
    private val sheetPaddingBottom = resources.getDimensionPixelSize(R.dimen.ds_phone_sheet_padding_bottom)
    private val sheetPaddingHorizontal = resources.getDimensionPixelSize(R.dimen.ds_phone_sheet_padding_horizontal)

    val siteSettings: SiteSettingsView get() = vb.vSiteSettings

    /** Enabled while the sheet is open */
    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
    }

    val isOpen: Boolean get() = isVisible

    init {
        visibility = GONE
        vb.vScrim.setOnClickListener { hide() }
    }

    /** The sheet keeps out of the navigation bar; the scrim covers it */
    fun setInsets(left: Int, right: Int, bottom: Int) {
        vb.llSheet.setPadding(sheetPaddingHorizontal + left, vb.llSheet.paddingTop, sheetPaddingHorizontal + right,
            sheetPaddingBottom + bottom)
    }

    fun render(state: CurrentTabState) {
        vb.vSiteSettings.render(state)
        //another page without a site
        if (state.host.isEmpty()) hide()
    }

    fun show() {
        //it may be closing
        if (backCallback.isEnabled) return
        vb.vScrim.animate().cancel()
        vb.llSheet.animate().cancel()
        visibility = VISIBLE
        backCallback.isEnabled = true
        vb.vScrim.alpha = 0f
        vb.vScrim.animate().alpha(1f).setDuration(ANIMATION_DURATION).start()
        vb.llSheet.visibility = INVISIBLE
        vb.llSheet.post {
            vb.llSheet.translationY = vb.llSheet.height.toFloat()
            vb.llSheet.visibility = VISIBLE
            vb.llSheet.animate().translationY(0f).setDuration(ANIMATION_DURATION)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun hide() {
        if (!isOpen || !backCallback.isEnabled) return
        backCallback.isEnabled = false
        vb.vScrim.animate().alpha(0f).setDuration(ANIMATION_DURATION).start()
        vb.llSheet.animate().translationY(vb.llSheet.height.toFloat()).setDuration(ANIMATION_DURATION)
            .withEndAction { visibility = GONE }.start()
    }

    companion object {
        private const val ANIMATION_DURATION = 200L
    }
}
