package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.HistoryNavigator
import com.phlox.tvwebbrowser.activity.main.HistoryNavigator.Screen
import com.phlox.tvwebbrowser.databinding.ViewDownloadsSectionBinding
import com.phlox.tvwebbrowser.databinding.ViewHistoryRowBinding
import com.phlox.tvwebbrowser.databinding.ViewHistoryScreenBinding
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.widgets.datedlist.DatedListAdapter
import com.phlox.tvwebbrowser.widgets.datedlist.DatedRow
import com.phlox.tvwebbrowser.widgets.datedlist.ListDividerDecoration
import com.phlox.tvwebbrowser.widgets.datedlist.StickyHeaderDecoration
import com.phlox.tvwebbrowser.widgets.datedlist.StickyHeaderLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The history on TV, over the whole window: the field of the search (with the number found) and Clear all
 * in the header, the history by days, the day pinned at the top while scrolling. OK opens the page of the
 * focused row (it says so), Right reaches its options, a long OK starts the selection mode, where OK
 * chooses rows and the header has Select all and Delete. Back leaves the selection mode, then closes the
 * screen; [onClosed] gives the focus back.
 */
class HistoryScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private lateinit var scope: CoroutineScope
    private lateinit var menu: PopupMenuView
    private lateinit var navigator: HistoryNavigator
    private lateinit var callback: HistoryNavigator.ShellCallback
    private lateinit var onClosed: () -> Unit
    private val vb: ViewHistoryScreenBinding
    private val adapter = Adapter()
    private val binder = HistoryRowBinder(context)
    private var screen: Screen? = null

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!navigator.onBack()) hide()
        }
    }

    val isOpen: Boolean get() = isVisible

    init {
        orientation = VERTICAL
        visibility = GONE
        //clicks and the cursor do not reach the page under it
        isClickable = true
        setBackgroundColor(context.themeColor(R.attr.tbColorDsSurface))
        vb = ViewHistoryScreenBinding.inflate(LayoutInflater.from(context), this)
        val stickyHeader = StickyHeaderDecoration(adapter)
        vb.rvItems.layoutManager = StickyHeaderLayoutManager(context, stickyHeader)
        vb.rvItems.itemAnimator = null
        vb.rvItems.addItemDecoration(ListDividerDecoration(vb.rvItems, adapter, R.attr.tbColorDsLine, R.dimen.ds_border_hairline))
        vb.rvItems.addItemDecoration(stickyHeader)
        vb.rvItems.adapter = adapter
        vb.ibBack.setOnClickListener { if (!navigator.onBack()) hide() }
        vb.llClearAll.setOnClickListener { navigator.clearAll() }
        vb.llSelectAll.setOnClickListener { navigator.selectAll() }
        vb.llDelete.setOnClickListener { navigator.deleteSelected() }
        vb.ibClearSearch.setOnClickListener {
            vb.etSearch.setText("")
            vb.etSearch.requestFocus()
        }
        vb.ibVoice.setOnClickListener {
            hideKeyboard()
            callback.startVoiceSearch { text ->
                if (!isOpen) return@startVoiceSearch
                vb.etSearch.setText(text)
                vb.etSearch.setSelection(vb.etSearch.length())
            }
        }
        vb.etSearch.doAfterTextChanged { text ->
            val query = text?.toString() ?: ""
            vb.ibClearSearch.isVisible = query.isNotEmpty()
            if (isOpen) navigator.search(query)
        }
        //the search key of the keyboard goes to the results
        vb.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            hideKeyboard()
            focusRow(null, 0)
            true
        }
    }

    fun attach(scope: CoroutineScope, menu: PopupMenuView, callback: HistoryNavigator.ShellCallback, onClosed: () -> Unit) {
        this.scope = scope
        this.menu = menu
        this.callback = callback
        this.onClosed = onClosed
        navigator = HistoryNavigator(context, TVBro.container.data.history, scope, object : HistoryNavigator.Host {
            override fun render(screen: Screen, focus: HistoryNavigator.Focus?) = this@HistoryScreenView.render(screen, focus)

            override fun open(url: String) {
                hide()
                callback.open(url)
            }

            override fun openInNewTab(url: String) {
                hide()
                callback.openInNewTab(url)
            }

            override fun copyLink(url: String) = copyLinkToClipboard(context, url)

            override fun showMessage(icon: Int, message: String) = callback.showMessage(icon, message)

            override fun confirm(message: String, action: () -> Unit) = callback.confirm(message, action)
        })
    }

    /** Opens the history, the latest page focused */
    fun show() {
        visibility = VISIBLE
        backCallback.isEnabled = true
        screen = null
        vb.etSearch.setText("")
        adapter.submitItems(emptyList())
        navigator.start()
    }

    fun hide() {
        if (!isOpen) return
        hideKeyboard()
        menu.hide()
        navigator.stop()
        visibility = GONE
        backCallback.isEnabled = false
        onClosed()
    }

    private fun render(screen: Screen, focus: HistoryNavigator.Focus?) {
        this.screen = screen
        renderHeader(screen)
        vb.tvEmpty.isVisible = screen.loaded && screen.items.isEmpty()
        vb.tvEmpty.text = historyEmptyText(context, screen)
        adapter.submitItems(screen.items) {
            //the selection and the words searched change rows that stay the same items
            adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_STATE)
            if (!isOpen) return@submitItems
            if (focus != null) {
                focusRow(focus.key, focus.index)
            } else if (findFocus() == null) {
                //the focused button went with the mode it belonged to
                focusRow(null, 0)
            }
        }
    }

    private fun renderHeader(screen: Screen) {
        val selecting = screen.selecting
        vb.ibBack.setImageResource(if (selecting) R.drawable.ic_close else R.drawable.ic_back)
        vb.ibBack.contentDescription = context.getString(if (selecting) R.string.leave_selection else R.string.back_to_browser)
        vb.tvTitle.text = historyTitle(context, screen)
        vb.llSearch.isVisible = !selecting
        vb.tvFound.isVisible = !selecting && screen.searching
        vb.tvFound.text = resources.getQuantityString(R.plurals.history_found, screen.items.size, screen.items.size)
        vb.llClearAll.isVisible = !selecting
        vb.llSelectAll.isVisible = selecting
        vb.llDelete.isVisible = selecting
    }

    private fun showRowMenu(item: HistoryItem, anchor: View) {
        if (navigator.isSelecting) return
        val items = navigator.actionsOf(item).map { action ->
            PopupMenuView.Item(action.icon, context.getString(action.label)) { navigator.perform(item, action) }
        }
        menu.show(anchor, items, alignEnd = true)
    }

    /** Focuses the row of the [key], else the row at [index] (the last one if there are fewer), else the field */
    private fun focusRow(key: String?, index: Int) {
        val items = adapter.items
        var itemIndex = items.indexOfFirst { HistoryNavigator.keyOf(it.id) == key }
        if (itemIndex == -1) itemIndex = minOf(index, items.size - 1)
        if (itemIndex < 0) {
            focusHeader()
            return
        }
        val id = items[itemIndex].id
        val position = (0 until adapter.itemCount).firstOrNull {
            (adapter.row(it) as? DatedRow.Item)?.value?.id == id
        } ?: return
        vb.rvItems.scrollToPosition(position)
        requestRowFocus(position, ROW_FOCUS_ATTEMPTS)
    }

    /** The field while it has a search (nothing found), else Back: a focused field may open the keyboard */
    private fun focusHeader() {
        if (vb.llSearch.isVisible && vb.etSearch.length() > 0) vb.etSearch.requestFocus() else vb.ibBack.requestFocus()
    }

    /** The row's view exists after the next layout of the list */
    private fun requestRowFocus(position: Int, attempts: Int) {
        vb.rvItems.post {
            if (!isOpen) return@post
            val holder = vb.rvItems.findViewHolderForAdapterPosition(position) as? Holder
            when {
                holder != null -> holder.vb.llMain.requestFocus()
                attempts > 0 -> requestRowFocus(position, attempts - 1)
                else -> focusHeader()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Keys move the focus only inside the screen, not to the bar under it; Up from the list goes to the field */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!isOpen) return super.focusSearch(focused, direction)
        val next = FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: return focused
        if (direction == View.FOCUS_UP && vb.llSearch.isVisible && focused != null && isInside(focused, vb.rvItems) &&
            isInside(next, vb.llHeader)) {
            return vb.etSearch
        }
        return next
    }

    private fun isInside(view: View, parent: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    private inner class Adapter : DatedListAdapter<HistoryItem>(HistoryItemDiff) {
        override fun timeOf(item: HistoryItem) = item.time

        override fun headerTitle(day: Long): String = sectionTitle(context, day)

        override fun createHeaderViewHolder(parent: ViewGroup): HeaderViewHolder {
            val vb = ViewDownloadsSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return HeaderViewHolder(vb.root, vb.tvTitle, ::headerTitle)
        }

        override fun onCreateItemViewHolder(parent: ViewGroup): RecyclerView.ViewHolder =
            Holder(ViewHistoryRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindItem(holder: RecyclerView.ViewHolder, item: HistoryItem, payloads: List<Any>) =
            (holder as Holder).bind(item)
    }

    private inner class Holder(val vb: ViewHistoryRowBinding) : RecyclerView.ViewHolder(vb.root) {
        private var item: HistoryItem? = null
        private val views = HistoryRowViews(vb.llMain, vb.ivCheck, vb.tvTime, vb.ivIcon, vb.tvTitle, vb.tvCaption, vb.ibOptions)

        init {
            vb.llMain.setOnClickListener { item?.let { navigator.onRowClick(it) } }
            vb.llMain.setOnLongClickListener {
                item?.let { navigator.onRowLongClick(it) }
                true
            }
            //the Menu key opens the options from the row and from its button
            val menuKey = View.OnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_MENU) return@OnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) item?.let { showRowMenu(it, vb.ibOptions) }
                true
            }
            vb.llMain.setOnKeyListener(menuKey)
            vb.ibOptions.setOnKeyListener(menuKey)
            vb.llMain.setOnFocusChangeListener { _, _ -> updateHint() }
            vb.ibOptions.setOnClickListener { item?.let { showRowMenu(it, vb.ibOptions) } }
        }

        fun bind(item: HistoryItem) {
            val screen = screen ?: return
            val newIcon = this.item?.id != item.id || this.item?.url != item.url
            this.item = item
            binder.bind(item, screen, views, newIcon) { url -> loadFavicon(item, url) }
            updateHint()
        }

        private fun loadFavicon(item: HistoryItem, url: String) {
            scope.launch {
                val favicon = FaviconsPool.get(url) ?: return@launch
                //the holder may show another item by now
                if (this@Holder.item?.id != item.id) return@launch
                ImageViewCompat.setImageTintList(vb.ivIcon, null)
                vb.ivIcon.setImageBitmap(favicon)
            }
        }

        /** The focused row tells what OK does */
        private fun updateHint() {
            val item = item
            val screen = screen
            val hint = when {
                item == null || screen == null || !vb.llMain.hasFocus() -> 0
                !screen.selecting -> R.string.open
                !screen.isSelected(item) -> R.string.select
                else -> 0
            }
            vb.tvHint.isVisible = hint != 0
            if (hint != 0) vb.tvHint.setText(hint)
        }
    }

    companion object {
        private const val ROW_FOCUS_ATTEMPTS = 3
        private const val PAYLOAD_STATE = "state"
    }
}
