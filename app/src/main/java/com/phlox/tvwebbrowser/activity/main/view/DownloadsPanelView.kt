package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.DownloadRowText
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Action
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Screen
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.databinding.ViewDownloadRowBinding
import com.phlox.tvwebbrowser.databinding.ViewDownloadsPanelBinding
import com.phlox.tvwebbrowser.widgets.datedlist.ListDividerDecoration
import kotlinx.coroutines.CoroutineScope

/**
 * The TV downloads panel, under the Downloads button of the bar, over the page: the latest downloads,
 * those in progress first ([DownloadsNavigator.Look.PANEL]). OK on a row opens (installs) the file or
 * downloads a failed one again; Right reaches the buttons of the row (pause or resume, cancel, download
 * again) and its options (also the Menu key and a long OK). All downloads, in the header, opens the full
 * screen. Back closes the panel; the Downloads button gets the focus back.
 */
class DownloadsPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AnchoredOverlayView(context, attrs) {
    private lateinit var menu: PopupMenuView
    private lateinit var navigator: DownloadsNavigator
    private val text = DownloadRowText(context)

    private val vb = ViewDownloadsPanelBinding.inflate(LayoutInflater.from(context), this)
    override val panel: View get() = vb.llPanel
    private val adapter = Adapter()
    /** The panel is shown once its rows are ready */
    private var pendingShow: (() -> Unit)? = null

    init {
        vb.llPanel.clipToOutline = true
        vb.rvItems.layoutManager = LinearLayoutManager(context)
        vb.rvItems.itemAnimator = null
        vb.rvItems.adapter = adapter
        vb.rvItems.addItemDecoration(ListDividerDecoration(vb.rvItems, adapter, R.attr.tbColorDsLine, R.dimen.ds_border_hairline))
    }

    /** [showAll] opens all downloads, with the download of the id focused, if any */
    fun attach(scope: CoroutineScope, menu: PopupMenuView, callback: DownloadsNavigator.ShellCallback,
               showAll: (Long?) -> Unit) {
        this.menu = menu
        vb.btnAll.setOnClickListener {
            hide()
            showAll(null)
        }
        navigator = DownloadsNavigator(context, callback.downloads, callback.downloadCommands, callback.downloadDevice,
            scope, object : DownloadsNavigator.Host {
                override fun render(screen: Screen, focus: DownloadsNavigator.Focus?) = this@DownloadsPanelView.render(screen, focus)

                override fun showMessage(icon: Int, message: String) = callback.showMessage(icon, message)

                override fun confirmClearAll(request: DownloadsNavigator.ClearAllRequest, onConfirm: (Boolean) -> Unit) =
                    callback.confirmClearAll(request, onConfirm)

                override fun showAll(focusId: Long?) {
                    hide()
                    showAll(focusId)
                }
            }, DownloadsNavigator.Look.PANEL)
    }

    /** Opens the panel under the [anchor] (the Downloads button), its end under the end of [endAlignTo] */
    fun show(anchor: View, endAlignTo: View) {
        if (isOpen) return
        pendingShow = {
            anchor.isActivated = true
            showUnder(anchor, resources.getDimensionPixelSize(R.dimen.ds_site_panel_gap),
                if (adapter.itemCount == 0) vb.btnAll else vb.rvItems, endAlignTo,
                resources.getDimensionPixelSize(R.dimen.ds_bookmarks_panel_margin_bottom))
        }
        navigator.start()
    }

    override fun onHidden() {
        menu.hide()
        navigator.stop()
        pendingShow = null
        anchor?.isActivated = false
    }

    private fun render(screen: Screen, focus: DownloadsNavigator.Focus?) {
        vb.tvEmpty.isVisible = screen.items.isEmpty()
        adapter.submitList(screen.items) {
            pendingShow?.let {
                pendingShow = null
                it()
            }
            if (focus == null || !isOpen) return@submitList
            if (focus.key == DownloadsNavigator.ALL_DOWNLOADS) vb.btnAll.requestFocus() else focusRow(focus.key, focus.index)
        }
    }

    private fun showRowMenu(item: DownloadItem, anchor: View) {
        val items = navigator.actionsOf(item).map { action ->
            PopupMenuView.Item(action.icon, context.getString(action.label)) { navigator.perform(item, action) }
        }
        if (items.isNotEmpty()) menu.show(anchor, items, alignEnd = true)
    }

    /** Focuses the row of the [key], else the row at [index] (the last one if there are fewer) */
    private fun focusRow(key: String?, index: Int) {
        val items = adapter.currentList
        var position = items.indexOfFirst { DownloadsNavigator.keyOf(it.id) == key }
        if (position == -1) position = minOf(index, items.size - 1)
        if (position < 0) {
            vb.btnAll.requestFocus()
            return
        }
        vb.rvItems.scrollToPosition(position)
        requestRowFocus(position, ROW_FOCUS_ATTEMPTS)
    }

    /** The row's view exists after the next layout of the list */
    private fun requestRowFocus(position: Int, attempts: Int) {
        vb.rvItems.post {
            if (!isOpen) return@post
            val holder = vb.rvItems.findViewHolderForAdapterPosition(position) as? Holder
            when {
                holder != null -> holder.vb.llMain.requestFocus()
                attempts > 0 -> requestRowFocus(position, attempts - 1)
                else -> vb.btnAll.requestFocus()
            }
        }
    }

    private inner class Adapter : ListAdapter<DownloadItem, Holder>(DownloadItemDiff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ViewDownloadRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }

    private inner class Holder(val vb: ViewDownloadRowBinding) : RecyclerView.ViewHolder(vb.root) {
        private var item: DownloadItem? = null
        private val views = DownloadRowViews(vb.ivIcon, vb.tvTitle, vb.tvCaption, vb.pbProgress,
            listOf(vb.ibFirst, vb.ibSecond), vb.ibOptions)

        init {
            vb.llMain.setOnClickListener { item?.let { navigator.onRowClick(it) } }
            vb.llMain.setOnLongClickListener {
                item?.let { showRowMenu(it, vb.ibOptions) }
                true
            }
            vb.llMain.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) item?.let { showRowMenu(it, vb.ibOptions) }
                true
            }
            vb.ibOptions.setOnClickListener { item?.let { showRowMenu(it, vb.ibOptions) } }
        }

        fun bind(item: DownloadItem) {
            this.item = item
            bindDownloadRow(item, navigator, text, views, withHost = false, withDay = true) { action: Action ->
                navigator.perform(item, action)
            }
        }
    }

    companion object {
        private const val ROW_FOCUS_ATTEMPTS = 3
    }
}
