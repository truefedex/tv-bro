package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewPopupMenuBinding
import com.phlox.tvwebbrowser.databinding.ViewPopupMenuRowBinding

/**
 * A menu of the given rows under the view that opened it (above it when there is no room under it),
 * the first row focused (or the given one). Rows that can not act are only dimmed, so the number of presses
 * to each row never changes. A menu of options marks the chosen one ([Item.checked]).
 */
class PopupMenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AnchoredOverlayView(context, attrs) {
    /**
     * @param checked for an option: true for the chosen one (selected, its [icon] shown), false for the others
     *   (no icon); null for an action
     */
    class Item(@DrawableRes val icon: Int, val label: String, val enabled: Boolean = true, val checked: Boolean? = null,
               val action: () -> Unit)

    private val vb = ViewPopupMenuBinding.inflate(LayoutInflater.from(context), this)
    override val panel: View get() = vb.llMenu
    private val defaultWidth = resources.getDimensionPixelSize(R.dimen.ds_bookmark_menu_width)
    /** The anchor is selected while the menu is open */
    private var activatedAnchor: View? = null

    init {
        vb.llMenu.clipToOutline = true
    }

    /**
     * @param alignEnd the menu's end goes under the end of [endAlignTo] (an anchor at the end of a row)
     * @param endAlignTo the view whose end the menu's end is under, the anchor if not given
     * @param gap between the anchor and the menu
     * @param width the width of the menu, the one of the menus of the panels if not given
     * @param activateAnchor the anchor is selected (activated) while the menu is open
     * @param focusIndex the row that gets the focus
     */
    fun show(anchor: View, items: List<Item>, alignEnd: Boolean, endAlignTo: View = anchor, gap: Int = 0,
             @DimenRes width: Int? = null, activateAnchor: Boolean = false, focusIndex: Int = 0) {
        val inflater = LayoutInflater.from(context)
        vb.llMenu.removeAllViews()
        var focusRow: View? = null
        for ((i, item) in items.withIndex()) {
            if (i > 0) {
                inflater.inflate(R.layout.view_popup_menu_separator, vb.llMenu, true)
            }
            val row = ViewPopupMenuRowBinding.inflate(inflater, vb.llMenu, true)
            row.ivIcon.setImageResource(item.icon)
            if (item.checked != null) {
                row.ivIcon.visibility = if (item.checked) VISIBLE else INVISIBLE
                row.root.isActivated = item.checked
            }
            row.tvLabel.text = item.label
            //disabled icons and labels are drawn at 40%
            row.root.alpha = if (item.enabled) 1f else DISABLED_ALPHA
            //the menu closes first: the action may move the focus elsewhere
            row.root.setOnClickListener {
                if (!item.enabled) return@setOnClickListener
                hide()
                item.action()
            }
            if (i == focusIndex || focusRow == null) focusRow = row.root
        }
        val margin = resources.getDimensionPixelSize(R.dimen.ds_bookmarks_panel_margin_bottom)
        vb.llMenu.layoutParams = vb.llMenu.layoutParams.apply {
            this.width = if (width == null) defaultWidth else resources.getDimensionPixelSize(width)
        }
        if (activateAnchor) {
            anchor.isActivated = true
            activatedAnchor = anchor
        }
        showUnder(anchor, gap, focusRow ?: return, if (alignEnd) endAlignTo else null, margin)
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

    override fun onHidden() {
        activatedAnchor?.isActivated = false
        activatedAnchor = null
    }

    companion object {
        private const val DISABLED_ALPHA = 0.4f
    }
}
