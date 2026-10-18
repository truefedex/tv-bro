package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewPhoneActionsRowBinding
import com.phlox.tvwebbrowser.databinding.ViewPhoneActionsSheetBinding

/**
 * A sheet of actions of the phone UI from the bottom over a scrim, with an optional header (what the
 * actions are for). Rows that can not act are only dimmed. A tap on the scrim or Back ([backCallback])
 * closes it; a tap on a row closes it and then acts.
 */
class PhoneActionsSheet @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val vb = ViewPhoneActionsSheetBinding.inflate(LayoutInflater.from(context), this)
    private val sheetPaddingBottom = resources.getDimensionPixelSize(R.dimen.ds_phone_sheet_padding_bottom)
    private val sheetPaddingHorizontal = resources.getDimensionPixelSize(R.dimen.ds_phone_sheet_padding_horizontal)

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

    /**
     * @param title what the actions are for (null for no header), [caption] the line under it
     */
    fun show(title: String?, caption: String?, items: List<PopupMenuView.Item>) {
        vb.llHeader.isVisible = title != null
        vb.vHeaderSeparator.isVisible = title != null
        vb.tvTitle.text = title
        vb.tvCaption.text = caption
        vb.tvCaption.isVisible = caption != null
        vb.llItems.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for (item in items) {
            val row = ViewPhoneActionsRowBinding.inflate(inflater, vb.llItems, true)
            row.ivIcon.setImageResource(item.icon)
            row.tvLabel.text = item.label
            //disabled icons and labels are drawn at 40%
            row.root.alpha = if (item.enabled) 1f else DISABLED_ALPHA
            row.root.setOnClickListener {
                if (!item.enabled) return@setOnClickListener
                hide()
                item.action()
            }
        }

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
        private const val DISABLED_ALPHA = 0.4f
    }
}
