package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.DrawableRes
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewPopupMenuBinding
import com.phlox.tvwebbrowser.databinding.ViewPopupMenuRowBinding

/**
 * A menu of the given rows under the view that opened it (above it when there is no room under it),
 * the first row focused. Rows that can not act are only dimmed, so the number of presses to each row
 * never changes.
 */
class PopupMenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AnchoredOverlayView(context, attrs) {
    class Item(@DrawableRes val icon: Int, val label: String, val enabled: Boolean = true, val action: () -> Unit)

    private val vb = ViewPopupMenuBinding.inflate(LayoutInflater.from(context), this)
    override val panel: View get() = vb.llMenu

    init {
        vb.llMenu.clipToOutline = true
    }

    /**
     * @param alignEnd the menu's end goes under the anchor's end (an anchor at the end of a row)
     */
    fun show(anchor: View, items: List<Item>, alignEnd: Boolean) {
        val inflater = LayoutInflater.from(context)
        vb.llMenu.removeAllViews()
        var firstRow: View? = null
        for ((i, item) in items.withIndex()) {
            if (i > 0) {
                inflater.inflate(R.layout.view_popup_menu_separator, vb.llMenu, true)
            }
            val row = ViewPopupMenuRowBinding.inflate(inflater, vb.llMenu, true)
            row.ivIcon.setImageResource(item.icon)
            row.tvLabel.text = item.label
            //disabled icons and labels are drawn at 40%
            row.root.alpha = if (item.enabled) 1f else DISABLED_ALPHA
            //the menu closes first: the action may move the focus elsewhere
            row.root.setOnClickListener {
                if (!item.enabled) return@setOnClickListener
                hide()
                item.action()
            }
            if (firstRow == null) firstRow = row.root
        }
        val margin = resources.getDimensionPixelSize(R.dimen.ds_bookmarks_panel_margin_bottom)
        showUnder(anchor, 0, firstRow ?: return, if (alignEnd) anchor else null, margin)
        //no room under the anchor: the menu goes above it
        val screenHeight = (parent as View).height
        val lp = vb.llMenu.layoutParams as LayoutParams
        vb.llMenu.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val menuHeight = vb.llMenu.measuredHeight
        if (lp.topMargin + menuHeight > screenHeight - margin) {
            lp.topMargin = maxOf(margin, lp.topMargin - anchor.height - menuHeight)
            vb.llMenu.layoutParams = lp
        }
    }

    companion object {
        private const val DISABLED_ALPHA = 0.4f
    }
}
