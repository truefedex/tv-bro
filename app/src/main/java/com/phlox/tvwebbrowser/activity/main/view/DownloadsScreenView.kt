package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.DownloadRowText
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Kind
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Screen
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.databinding.ViewDownloadRowBinding
import com.phlox.tvwebbrowser.databinding.ViewDownloadsScreenBinding
import com.phlox.tvwebbrowser.databinding.ViewDownloadsSectionBinding
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.widgets.datedlist.DatedListAdapter
import com.phlox.tvwebbrowser.widgets.datedlist.DatedRow
import com.phlox.tvwebbrowser.widgets.datedlist.ListDividerDecoration
import com.phlox.tvwebbrowser.widgets.datedlist.StickyHeaderDecoration
import com.phlox.tvwebbrowser.widgets.datedlist.StickyHeaderLayoutManager
import kotlinx.coroutines.CoroutineScope

/**
 * All downloads on TV, over the whole window ([DownloadsNavigator.Look.SCREEN]): the filters by the kind
 * of the file, Open folder (with the free space) and Clear all in the header; the downloads in progress and
 * paused first, then the others by days, the day pinned at the top while scrolling. The focused row tells
 * what OK does; Right reaches its buttons and its options. Back closes it; [onClosed] gives the focus back.
 */
class DownloadsScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private lateinit var menu: PopupMenuView
    private lateinit var navigator: DownloadsNavigator
    private lateinit var onClosed: () -> Unit
    private val text = DownloadRowText(context)
    private val vb: ViewDownloadsScreenBinding
    private val adapter = Adapter()
    private var filters: List<Kind>? = null

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
    }

    val isOpen: Boolean get() = isVisible

    init {
        orientation = VERTICAL
        visibility = GONE
        //clicks and the cursor do not reach the page under it
        isClickable = true
        setBackgroundColor(context.themeColor(R.attr.tbColorDsSurface))
        vb = ViewDownloadsScreenBinding.inflate(LayoutInflater.from(context), this)
        val stickyHeader = StickyHeaderDecoration(adapter)
        vb.rvItems.layoutManager = StickyHeaderLayoutManager(context, stickyHeader)
        vb.rvItems.itemAnimator = null
        vb.rvItems.addItemDecoration(ListDividerDecoration(vb.rvItems, adapter, R.attr.tbColorDsLine, R.dimen.ds_border_hairline))
        vb.rvItems.addItemDecoration(stickyHeader)
        vb.rvItems.adapter = adapter
        vb.llFilters.clipToOutline = true
        vb.ibBack.setOnClickListener { hide() }
        vb.llOpenFolder.setOnClickListener { navigator.openFolder() }
        vb.llClearAll.setOnClickListener { navigator.clearAll() }
    }

    fun attach(scope: CoroutineScope, menu: PopupMenuView, callback: DownloadsNavigator.ShellCallback, onClosed: () -> Unit) {
        this.menu = menu
        this.onClosed = onClosed
        navigator = DownloadsNavigator(context, callback.downloads, callback.downloadCommands, callback.downloadDevice,
            scope, object : DownloadsNavigator.Host {
                override fun render(screen: Screen, focus: DownloadsNavigator.Focus?) = this@DownloadsScreenView.render(screen, focus)

                override fun showMessage(icon: Int, message: String) = callback.showMessage(icon, message)

                override fun confirmClearAll(request: DownloadsNavigator.ClearAllRequest, onConfirm: (Boolean) -> Unit) =
                    callback.confirmClearAll(request, onConfirm)

                override fun showAll(focusId: Long?) = show(focusId)
            }, DownloadsNavigator.Look.SCREEN)
    }

    /** Opens all downloads, the download [focusId] focused (else the first one) */
    fun show(focusId: Long? = null) {
        visibility = VISIBLE
        backCallback.isEnabled = true
        filters = null
        navigator.start(focusId)
    }

    fun hide() {
        if (!isOpen) return
        menu.hide()
        navigator.stop()
        visibility = GONE
        backCallback.isEnabled = false
        onClosed()
    }

    private fun render(screen: Screen, focus: DownloadsNavigator.Focus?) {
        renderFilters(screen)
        vb.tvFreeSpace.text = context.getString(R.string.free_space, text.size(screen.freeSpace))
        vb.tvEmpty.isVisible = screen.items.isEmpty()
        adapter.submitItems(screen.items) {
            if (focus == null || !isOpen) return@submitItems
            if (focus.key?.startsWith("filter:") == true) {
                vb.llFilters.findViewWithTag<View>(focus.key)?.requestFocus()
            } else {
                focusRow(focus.key, focus.index)
            }
        }
    }

    /** All and the filters of the kinds there are files of, as segments of a group */
    private fun renderFilters(screen: Screen) {
        if (filters != screen.filters) {
            filters = screen.filters
            vb.llFilters.removeAllViews()
            val inflater = LayoutInflater.from(context)
            for ((i, kind) in (listOf<Kind?>(null) + screen.filters).withIndex()) {
                if (i > 0) inflater.inflate(R.layout.view_toolbar_separator, vb.llFilters, true)
                val button = inflater.inflate(R.layout.view_download_filter, vb.llFilters, false) as TextView
                button.setText(filterLabel(kind))
                button.tag = DownloadsNavigator.filterKey(kind)
                button.setOnClickListener { navigator.setFilter(kind) }
                vb.llFilters.addView(button)
            }
        }
        vb.llFilters.isVisible = screen.filters.size > 1
        val chosen = DownloadsNavigator.filterKey(screen.filter)
        for (i in 0 until vb.llFilters.childCount) {
            val child = vb.llFilters.getChildAt(i)
            child.isActivated = child.tag == chosen
        }
    }

    private fun showRowMenu(item: DownloadItem, anchor: View) {
        val items = navigator.actionsOf(item).map { action ->
            PopupMenuView.Item(action.icon, context.getString(action.label)) { navigator.perform(item, action) }
        }
        if (items.isNotEmpty()) menu.show(anchor, items, alignEnd = true)
    }

    /** Focuses the row of the [key], else the row at [index] of the downloads (the last one if there are fewer) */
    private fun focusRow(key: String?, index: Int) {
        val items = adapter.items
        var itemIndex = items.indexOfFirst { DownloadsNavigator.keyOf(it.id) == key }
        if (itemIndex == -1) itemIndex = minOf(index, items.size - 1)
        if (itemIndex < 0) {
            vb.ibBack.requestFocus()
            return
        }
        val id = items[itemIndex].id
        val position = (0 until adapter.itemCount).firstOrNull {
            (adapter.row(it) as? DatedRow.Item)?.value?.id == id
        } ?: return
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
                else -> vb.ibBack.requestFocus()
            }
        }
    }

    /** Keys move the focus only inside the screen, not to the bar under it */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!isOpen) return super.focusSearch(focused, direction)
        return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }

    private inner class Adapter : DatedListAdapter<DownloadItem>(DownloadItemDiff) {
        override fun timeOf(item: DownloadItem) = item.time

        override fun isPinned(item: DownloadItem) = item.isActive

        override fun headerTitle(day: Long): String = sectionTitle(context, day)

        override fun createHeaderViewHolder(parent: ViewGroup): HeaderViewHolder {
            val vb = ViewDownloadsSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return HeaderViewHolder(vb.root, vb.tvTitle, ::headerTitle)
        }

        override fun onCreateItemViewHolder(parent: ViewGroup): RecyclerView.ViewHolder =
            Holder(ViewDownloadRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindItem(holder: RecyclerView.ViewHolder, item: DownloadItem, payloads: List<Any>) =
            (holder as Holder).bind(item)
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
            //the Menu key opens the options from the row and from its buttons
            val menuKey = View.OnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_MENU) return@OnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) item?.let { showRowMenu(it, vb.ibOptions) }
                true
            }
            for (view in listOf(vb.llMain, vb.ibFirst, vb.ibSecond, vb.ibOptions)) view.setOnKeyListener(menuKey)
            vb.llMain.setOnFocusChangeListener { _, _ -> updateHint() }
            vb.ibOptions.setOnClickListener { item?.let { showRowMenu(it, vb.ibOptions) } }
        }

        fun bind(item: DownloadItem) {
            this.item = item
            bindDownloadRow(item, navigator, text, views, withHost = true, withDay = false) { action ->
                navigator.perform(item, action)
            }
            updateHint()
        }

        /** The focused row tells what OK does */
        private fun updateHint() {
            val action = item?.let { navigator.primaryAction(it) }
            vb.tvHint.isVisible = vb.llMain.hasFocus() && action != null
            if (action != null) vb.tvHint.setText(action.label)
        }
    }

    companion object {
        private const val ROW_FOCUS_ATTEMPTS = 3
    }
}
