package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.RelativeLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.HistoryNavigator
import com.phlox.tvwebbrowser.activity.main.HistoryNavigator.Screen
import com.phlox.tvwebbrowser.databinding.ViewPhoneDownloadsSectionBinding
import com.phlox.tvwebbrowser.databinding.ViewPhoneHistoryBinding
import com.phlox.tvwebbrowser.databinding.ViewPhoneHistoryRowBinding
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.widgets.NotificationView
import com.phlox.tvwebbrowser.widgets.datedlist.DatedListAdapter
import com.phlox.tvwebbrowser.widgets.datedlist.DatedRow
import com.phlox.tvwebbrowser.widgets.datedlist.ListDividerDecoration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The history page of the phone UI over the whole window, like the downloads: the history by days, a tap
 * opens the page, ⋮ of a row opens the sheet of its actions, a long press starts the selection mode (the
 * check boxes take the place of the favicons, the header has Select all and Delete). The search button
 * turns the title into the field of the search; ⋮ of the header has Clear all. Back ([backCallback])
 * leaves the selection mode, then the search, then closes the page.
 */
class PhoneHistoryView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {
    private val vb = ViewPhoneHistoryBinding.inflate(LayoutInflater.from(context), this)
    private lateinit var scope: CoroutineScope
    private lateinit var sheet: PhoneActionsSheet
    private lateinit var navigator: HistoryNavigator
    private lateinit var callback: HistoryNavigator.ShellCallback
    private val adapter = Adapter()
    private val binder = HistoryRowBinder(context)
    private var screen: Screen? = null
    /** The header shows the field of the search */
    private var searchOpen = false

    /** Enabled while the history is open */
    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = onBack()
    }

    val isOpen: Boolean get() = isVisible

    init {
        //under the status bar it has the color of its header
        setBackgroundColor(context.themeColor(R.attr.tbColorDsBgChrome))
        //touches do not go through to the page
        isClickable = true
        visibility = GONE
        vb.rvItems.layoutManager = LinearLayoutManager(context)
        vb.rvItems.itemAnimator = null
        vb.rvItems.addItemDecoration(ListDividerDecoration(vb.rvItems, adapter, R.attr.tbColorDsLine, R.dimen.ds_border_hairline))
        vb.rvItems.adapter = adapter
        vb.ibBack.setOnClickListener { onBack() }
        vb.ibSearch.setOnClickListener { openSearch() }
        vb.ibMenu.setOnClickListener {
            sheet.show(null, null, listOf(PopupMenuView.Item(R.drawable.ic_clear_all, context.getString(R.string.clear_all)) {
                navigator.clearAll()
            }))
        }
        vb.ibSelectAll.setOnClickListener { navigator.selectAll() }
        vb.ibDelete.setOnClickListener { navigator.deleteSelected() }
        vb.ibClearSearch.setOnClickListener { vb.etSearch.setText("") }
        vb.ibVoice.setOnClickListener {
            hideKeyboard()
            callback.startVoiceSearch { text ->
                if (!isOpen || !searchOpen) return@startVoiceSearch
                vb.etSearch.setText(text)
                vb.etSearch.setSelection(vb.etSearch.length())
            }
        }
        vb.etSearch.doAfterTextChanged { text ->
            val query = text?.toString() ?: ""
            vb.ibClearSearch.isVisible = query.isNotEmpty()
            vb.ibVoice.isVisible = query.isEmpty()
            if (isOpen) navigator.search(query)
        }
        vb.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            hideKeyboard()
            true
        }
    }

    fun attach(scope: CoroutineScope, sheet: PhoneActionsSheet, callback: HistoryNavigator.ShellCallback) {
        this.scope = scope
        this.sheet = sheet
        this.callback = callback
        navigator = HistoryNavigator(context, TVBro.container.data.history, scope, object : HistoryNavigator.Host {
            override fun render(screen: Screen, focus: HistoryNavigator.Focus?) = this@PhoneHistoryView.render(screen, focus)

            override fun open(url: String) {
                hide()
                callback.open(url)
            }

            override fun openInNewTab(url: String) {
                hide()
                callback.openInNewTab(url)
            }

            override fun copyLink(url: String) = copyLinkToClipboard(context, url)

            //over the history while it is open
            override fun showMessage(icon: Int, message: String) {
                if (isOpen) NotificationView.show(this@PhoneHistoryView, icon, message) else callback.showMessage(icon, message)
            }

            override fun confirm(message: String, action: () -> Unit) = callback.confirm(message, action)
        })
    }

    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, keyboard: Int) {
        setPadding(left, top, right, 0)
        //the list scrolls under the navigation bar and the keyboard
        vb.rvItems.setPadding(0, 0, 0, maxOf(bottom, keyboard))
    }

    /** Opens the history at its top */
    fun show() {
        searchOpen = false
        screen = null
        vb.etSearch.setText("")
        adapter.submitItems(emptyList())
        navigator.start()
        if (isOpen && backCallback.isEnabled) return
        backCallback.isEnabled = true
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f
        translationY = height * HIDDEN_SHIFT
        animate().alpha(1f).translationY(0f).setDuration(ANIMATION_DURATION).start()
    }

    fun hide() {
        if (!isOpen) return
        hideKeyboard()
        sheet.hide()
        navigator.stop()
        backCallback.isEnabled = false
        animate().cancel()
        animate().alpha(0f).translationY(height * HIDDEN_SHIFT).setDuration(ANIMATION_DURATION)
            .withEndAction { visibility = GONE }.start()
    }

    private fun onBack() {
        when {
            navigator.onBack() -> {}
            searchOpen -> closeSearch()
            else -> hide()
        }
    }

    private fun openSearch() {
        searchOpen = true
        screen?.let { renderHeader(it) }
        vb.etSearch.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(vb.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchOpen = false
        hideKeyboard()
        vb.etSearch.clearFocus()
        vb.etSearch.setText("")
        screen?.let { renderHeader(it) }
    }

    private fun render(screen: Screen, focus: HistoryNavigator.Focus?) {
        this.screen = screen
        renderHeader(screen)
        vb.tvEmpty.isVisible = screen.loaded && screen.items.isEmpty()
        vb.tvEmpty.text = historyEmptyText(context, screen)
        adapter.submitItems(screen.items) {
            //the selection and the words searched change rows that stay the same items
            adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_STATE)
            //a touch screen has no focus: the row to focus is scrolled into view
            focus ?: return@submitItems
            val position = (0 until adapter.itemCount).firstOrNull {
                (adapter.row(it) as? DatedRow.Item)?.value?.let { item -> HistoryNavigator.keyOf(item.id) } == focus.key
            }
            val layoutManager = vb.rvItems.layoutManager as LinearLayoutManager
            when {
                position != null -> {
                    val first = layoutManager.findFirstCompletelyVisibleItemPosition()
                    val last = layoutManager.findLastCompletelyVisibleItemPosition()
                    if (position !in first..last) layoutManager.scrollToPositionWithOffset(position, vb.rvItems.height / 3)
                }
                focus.key == null && focus.index == 0 -> layoutManager.scrollToPosition(0)
            }
        }
    }

    private fun renderHeader(screen: Screen) {
        val selecting = screen.selecting
        val searching = !selecting && searchOpen
        vb.ibBack.setImageResource(if (selecting) R.drawable.ic_close else R.drawable.ic_back)
        vb.ibBack.contentDescription = context.getString(if (selecting) R.string.leave_selection else R.string.back)
        vb.tvTitle.text = historyTitle(context, screen)
        vb.tvTitle.isVisible = !searching
        vb.llSearch.isVisible = searching
        vb.ibSearch.isVisible = !selecting && !searching
        vb.ibMenu.isVisible = !selecting && !searching
        vb.ibSelectAll.isVisible = selecting
        vb.ibDelete.isVisible = selecting
    }

    private fun showRowSheet(item: HistoryItem) {
        val items = navigator.actionsOf(item).map { action ->
            PopupMenuView.Item(action.icon, context.getString(action.label)) { navigator.perform(item, action) }
        }
        sheet.show(HistoryRowBinder.titleOf(item), HistoryNavigator.withoutScheme(item.url), items)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private inner class Adapter : DatedListAdapter<HistoryItem>(HistoryItemDiff) {
        override fun timeOf(item: HistoryItem) = item.time

        override fun headerTitle(day: Long): String = sectionTitle(context, day)

        override fun createHeaderViewHolder(parent: ViewGroup): HeaderViewHolder {
            val vb = ViewPhoneDownloadsSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return HeaderViewHolder(vb.root, vb.tvTitle, ::headerTitle)
        }

        override fun onCreateItemViewHolder(parent: ViewGroup): RecyclerView.ViewHolder =
            Holder(ViewPhoneHistoryRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindItem(holder: RecyclerView.ViewHolder, item: HistoryItem, payloads: List<Any>) =
            (holder as Holder).bind(item)
    }

    private inner class Holder(val vb: ViewPhoneHistoryRowBinding) : RecyclerView.ViewHolder(vb.root) {
        private var item: HistoryItem? = null
        /** The icon shows the favicon of the item (not the check box, nor the one of another item) */
        private var showsFavicon = false
        private val views = HistoryRowViews(vb.llMain, null, null, vb.ivIcon, vb.tvTitle, vb.tvCaption, vb.ibOptions)

        init {
            vb.llMain.setOnClickListener { item?.let { navigator.onRowClick(it) } }
            vb.llMain.setOnLongClickListener {
                item?.let { navigator.onRowLongClick(it) }
                true
            }
            vb.ibOptions.setOnClickListener { item?.let { showRowSheet(it) } }
        }

        fun bind(item: HistoryItem) {
            val screen = screen ?: return
            val newIcon = !showsFavicon || this.item?.id != item.id || this.item?.url != item.url
            this.item = item
            showsFavicon = binder.bind(item, screen, views, newIcon) { url -> loadFavicon(item, url) }
        }

        private fun loadFavicon(item: HistoryItem, url: String) {
            scope.launch {
                val favicon = FaviconsPool.get(url) ?: return@launch
                //the holder may show another item, or the check box, by now
                if (this@Holder.item?.id != item.id || !showsFavicon) return@launch
                ImageViewCompat.setImageTintList(vb.ivIcon, null)
                vb.ivIcon.setImageBitmap(favicon)
            }
        }
    }

    companion object {
        private const val HIDDEN_SHIFT = 0.05f
        private const val ANIMATION_DURATION = 200L
        private const val PAYLOAD_STATE = "state"
    }
}
