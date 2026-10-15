package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewTabMenuBinding
import com.phlox.tvwebbrowser.model.BrowserTab

/**
 * The menu of a tab of the strip, under the tab: Close tab (it gets the focus), Close other tabs,
 * New tab to the right, Move left, Move right. Rows that can not act on the tab are only dimmed,
 * so the number of presses to each row never changes.
 */
class TabMenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AnchoredOverlayView(context, attrs) {
    interface Callback {
        fun closeTab(tab: BrowserTab)
        fun closeOtherTabs(tab: BrowserTab)
        fun openNewTabToTheRight(tab: BrowserTab)
        fun moveTab(tab: BrowserTab, offset: Int)
    }

    var callback: Callback? = null
    private val vb = ViewTabMenuBinding.inflate(LayoutInflater.from(context), this)
    override val panel: View get() = vb.llMenu
    private var tab: BrowserTab? = null

    init {
        vb.llMenu.clipToOutline = true
        onRow(vb.llCloseTab) { callback?.closeTab(it) }
        onRow(vb.llCloseOtherTabs) { callback?.closeOtherTabs(it) }
        onRow(vb.llNewTabToTheRight) { callback?.openNewTabToTheRight(it) }
        onRow(vb.llMoveLeft) { callback?.moveTab(it, -1) }
        onRow(vb.llMoveRight) { callback?.moveTab(it, 1) }
    }

    /** The menu closes first: the action may move the focus elsewhere */
    private fun onRow(row: View, action: (BrowserTab) -> Unit) {
        row.setOnClickListener {
            if (row.alpha < 1f) return@setOnClickListener
            val tab = tab ?: return@setOnClickListener
            hide()
            action(tab)
        }
    }

    /**
     * Opens the menu under the [anchor] (the view of the [tab] in the strip).
     * @param index the place of the tab, [count] the number of tabs
     */
    fun show(tab: BrowserTab, anchor: View, index: Int, count: Int) {
        this.tab = tab
        setUsable(vb.llCloseOtherTabs, count > 1)
        setUsable(vb.llMoveLeft, index > 0)
        setUsable(vb.llMoveRight, index < count - 1)
        showUnder(anchor, resources.getDimensionPixelOffset(R.dimen.ds_tab_menu_gap), vb.llCloseTab)
    }

    private fun setUsable(row: ViewGroup, usable: Boolean) {
        //disabled icons and labels are drawn at 40%
        row.alpha = if (usable) 1f else DISABLED_ALPHA
    }

    companion object {
        private const val DISABLED_ALPHA = 0.4f
    }
}
