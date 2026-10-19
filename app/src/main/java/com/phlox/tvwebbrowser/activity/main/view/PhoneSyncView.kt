package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.PhoneSyncNavigator
import com.phlox.tvwebbrowser.activity.main.PhoneSyncNavigator.Screen
import com.phlox.tvwebbrowser.activity.main.PhoneSyncNavigator.TvStatus
import com.phlox.tvwebbrowser.databinding.ViewPhoneSyncBinding
import com.phlox.tvwebbrowser.databinding.ViewPhoneSyncRowBinding
import com.phlox.tvwebbrowser.sync.BookmarkSelection
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.widgets.datedlist.ListDividerDecoration

/**
 * The page of the phone UI that sends to TVs, over the whole window like the bookmarks: it draws the
 * screens of a [PhoneSyncNavigator] (the TVs, a TV, the tabs or the bookmarks to send). Back
 * ([backCallback]) goes to the previous screen and closes the page from the first one.
 */
class PhoneSyncView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {
    private val vb = ViewPhoneSyncBinding.inflate(LayoutInflater.from(context), this)
    private lateinit var navigator: PhoneSyncNavigator
    private val adapter = Adapter()
    private var screen: Screen? = null
    private val indent = resources.getDimensionPixelSize(R.dimen.ds_bookmark_folder_indent)

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!navigator.back()) hide()
        }
    }

    val isOpen: Boolean get() = isVisible

    init {
        //under the status bar it has the color of its header
        setBackgroundColor(context.themeColor(R.attr.tbColorDsBgChrome))
        isClickable = true
        visibility = GONE
        vb.rvItems.layoutManager = LinearLayoutManager(context)
        vb.rvItems.itemAnimator = null
        vb.rvItems.addItemDecoration(ListDividerDecoration(vb.rvItems, adapter, R.attr.tbColorDsLine, R.dimen.ds_border_hairline))
        vb.rvItems.adapter = adapter
        vb.ibBack.setOnClickListener { backCallback.handleOnBackPressed() }
        vb.ibMenu.setOnClickListener { (screen as? Screen.Tv)?.let { navigator.forget(it.tv) } }
        vb.ibSelectAll.setOnClickListener {
            when (val screen = screen) {
                is Screen.Tabs -> navigator.setAllTabs(!screen.tabs.all { it.checked })
                is Screen.Bookmarks -> navigator.setAllBookmarks(screen.all != BookmarkSelection.State.ALL)
                else -> {}
            }
        }
        vb.btnAction.setOnClickListener {
            when (screen) {
                is Screen.Tabs -> navigator.sendTabs()
                is Screen.Bookmarks -> navigator.sendBookmarks()
                else -> {}
            }
        }
    }

    fun attach(navigator: PhoneSyncNavigator) {
        this.navigator = navigator
    }

    fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        setPadding(left, top, right, bottom)
    }

    /** Opens the list of the TVs */
    fun show() {
        if (!isOpen || !backCallback.isEnabled) {
            backCallback.isEnabled = true
            animate().cancel()
            visibility = VISIBLE
            alpha = 0f
            translationY = height * HIDDEN_SHIFT
            animate().alpha(1f).translationY(0f).setDuration(ANIMATION_DURATION).start()
        }
        navigator.start()
    }

    fun hide() {
        if (!isOpen) return
        navigator.stop()
        backCallback.isEnabled = false
        animate().cancel()
        animate().alpha(0f).translationY(height * HIDDEN_SHIFT).setDuration(ANIMATION_DURATION)
            .withEndAction { visibility = GONE }.start()
    }

    fun render(screen: Screen, busy: String?) {
        val screenChanged = this.screen?.javaClass != screen.javaClass
        this.screen = screen
        vb.llBusy.isVisible = busy != null
        vb.tvBusy.text = busy
        vb.ibMenu.isVisible = screen is Screen.Tv
        vb.ibSelectAll.isVisible = screen is Screen.Tabs || screen is Screen.Bookmarks
        vb.llBottom.isVisible = screen is Screen.Tabs || screen is Screen.Bookmarks
        vb.tvEmpty.isVisible = false
        val rows: List<Row> = when (screen) {
            is Screen.Tvs -> {
                vb.tvTitle.setText(R.string.sync_send_to_tv)
                screen.tvs.map { Row.Tv(it) } + Row.Hint(context.getString(R.string.sync_how_to_pair))
            }
            is Screen.Tv -> {
                vb.tvTitle.text = screen.tv.name
                listOf(Row.Action(ACTION_TABS), Row.Action(ACTION_BOOKMARKS))
            }
            is Screen.Tabs -> {
                vb.tvTitle.setText(R.string.sync_send_tabs)
                val count = screen.tabs.count { it.checked }
                vb.btnAction.text = context.getString(R.string.sync_send_count, count)
                setSelectAllIcon(if (count == screen.tabs.size && count > 0) BookmarkSelection.State.ALL
                    else if (count == 0) BookmarkSelection.State.NONE else BookmarkSelection.State.PARTIAL)
                screen.tabs.mapIndexed { i, item -> Row.Tab(i, item) }
            }
            is Screen.Bookmarks -> {
                vb.tvTitle.setText(R.string.sync_send_bookmarks)
                vb.btnAction.setText(R.string.sync_next)
                setSelectAllIcon(screen.all)
                listOf<Row>(Row.Hint(context.getString(R.string.sync_bookmarks_hint))) + screen.rows.map { Row.Bookmark(it) }
            }
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        if (screenChanged) vb.rvItems.scrollToPosition(0)
    }

    private fun setSelectAllIcon(state: BookmarkSelection.State) {
        vb.ibSelectAll.setImageResource(checkIcon(state))
        vb.ibSelectAll.contentDescription = context.getString(
            if (state == BookmarkSelection.State.ALL) R.string.sync_select_none else R.string.sync_select_all)
    }

    private fun checkIcon(state: BookmarkSelection.State) = when (state) {
        BookmarkSelection.State.ALL -> R.drawable.ic_check_box
        BookmarkSelection.State.PARTIAL -> R.drawable.ic_check_box_partial
        BookmarkSelection.State.NONE -> R.drawable.ic_check_box_blank
    }

    private sealed class Row {
        class Tv(val item: PhoneSyncNavigator.TvItem) : Row()
        class Action(val action: Int) : Row()
        class Tab(val index: Int, val item: PhoneSyncNavigator.TabItem) : Row()
        class Bookmark(val row: BookmarkSelection.Row) : Row()
        class Hint(val text: String) : Row()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var rows: List<Row> = emptyList()

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ViewPhoneSyncRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])
    }

    private inner class Holder(val vb: ViewPhoneSyncRowBinding) : RecyclerView.ViewHolder(vb.root) {
        private var row: Row? = null
        private val accent = ColorStateList.valueOf(context.themeColor(R.attr.tbColorDsAccent))
        //the icons are tinted by themselves: a tint of the view replaces theirs
        private val iconColor = ColorStateList.valueOf(context.themeColor(R.attr.tbColorDsIcon))

        init {
            vb.llMain.setOnClickListener { onClick() }
            vb.ibCheck.setOnClickListener {
                when (val row = row) {
                    is Row.Tab -> navigator.toggleTab(row.index)
                    is Row.Bookmark -> navigator.toggleBookmark(row.row.node)
                    else -> {}
                }
            }
        }

        private fun onClick() {
            val screen = screen
            when (val row = row) {
                is Row.Tv -> navigator.openTv(row.item.tv)
                is Row.Action -> if (screen is Screen.Tv) {
                    if (row.action == ACTION_TABS) navigator.chooseTabs(screen.tv) else navigator.chooseBookmarks(screen.tv)
                }
                is Row.Tab -> navigator.toggleTab(row.index)
                is Row.Bookmark -> if (row.row.node.isFolder) navigator.expandFolder(row.row.node)
                    else navigator.toggleBookmark(row.row.node)
                is Row.Hint, null -> {}
            }
        }

        fun bind(row: Row) {
            this.row = row
            vb.vIndent.updateLayoutParams { width = 0 }
            vb.ibCheck.isVisible = false
            vb.ivIcon.isVisible = true
            vb.ivEnd.isVisible = false
            vb.tvTitle.isVisible = true
            vb.tvCaption.isVisible = true
            vb.llMain.isClickable = true
            vb.tvCaption.maxLines = 2
            when (row) {
                is Row.Tv -> {
                    vb.ivIcon.setImageResource(R.drawable.ic_tv)
                    vb.tvTitle.text = row.item.tv.name
                    vb.tvCaption.setText(when (row.item.status) {
                        TvStatus.CHECKING -> R.string.sync_status_checking
                        TvStatus.FOUND -> R.string.sync_status_found
                        TvStatus.NOT_FOUND -> R.string.sync_status_not_found
                        TvStatus.FORGOT_PHONE -> R.string.sync_status_forgot
                    })
                    vb.ivEnd.isVisible = true
                    vb.ivEnd.setImageResource(R.drawable.ic_chevron_right)
                }
                is Row.Action -> {
                    val tabs = row.action == ACTION_TABS
                    vb.ivIcon.setImageResource(if (tabs) R.drawable.ic_new_tab else R.drawable.ic_favorites)
                    vb.tvTitle.setText(if (tabs) R.string.sync_send_tabs else R.string.sync_send_bookmarks)
                    vb.tvCaption.setText(if (tabs) R.string.sync_send_tabs_summary else R.string.sync_send_bookmarks_summary)
                    vb.ivEnd.isVisible = true
                    vb.ivEnd.setImageResource(R.drawable.ic_chevron_right)
                }
                is Row.Tab -> {
                    bindCheck(if (row.item.checked) BookmarkSelection.State.ALL else BookmarkSelection.State.NONE)
                    vb.ivIcon.setImageResource(R.drawable.ic_site)
                    vb.tvTitle.text = row.item.tab.title.ifBlank { row.item.tab.url }
                    vb.tvCaption.text = row.item.tab.url
                    vb.tvCaption.maxLines = 1
                }
                is Row.Bookmark -> {
                    val node = row.row.node
                    vb.vIndent.updateLayoutParams { width = indent * row.row.depth }
                    bindCheck(row.row.state)
                    vb.ivIcon.setImageResource(when {
                        node.homePage -> R.drawable.ic_home
                        node.isFolder -> R.drawable.ic_folder
                        else -> R.drawable.ic_favorites
                    })
                    vb.tvTitle.text = node.title.ifBlank { node.url ?: "" }
                    vb.tvCaption.text = node.url ?: ""
                    vb.tvCaption.isVisible = node.url != null
                    vb.tvCaption.maxLines = 1
                    if (node.isFolder && node.children.isNotEmpty()) {
                        vb.ivEnd.isVisible = true
                        vb.ivEnd.setImageResource(if (node.expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
                    }
                }
                is Row.Hint -> {
                    vb.ivIcon.isVisible = false
                    vb.tvTitle.isVisible = false
                    vb.tvCaption.text = row.text
                    vb.tvCaption.maxLines = Int.MAX_VALUE
                    vb.llMain.isClickable = false
                }
            }
        }

        private fun bindCheck(state: BookmarkSelection.State) {
            vb.ibCheck.isVisible = true
            vb.ibCheck.setImageResource(checkIcon(state))
            ImageViewCompat.setImageTintList(vb.ibCheck, if (state == BookmarkSelection.State.NONE) iconColor else accent)
        }
    }

    companion object {
        private const val ANIMATION_DURATION = 200L
        private const val HIDDEN_SHIFT = 0.05f
        private const val ACTION_TABS = 0
        private const val ACTION_BOOKMARKS = 1
    }
}
