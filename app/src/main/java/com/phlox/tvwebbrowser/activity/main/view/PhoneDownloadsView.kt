package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.DownloadRowText
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Kind
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Screen
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.databinding.ViewPhoneDownloadRowBinding
import com.phlox.tvwebbrowser.databinding.ViewPhoneDownloadsBinding
import com.phlox.tvwebbrowser.databinding.ViewPhoneDownloadsSectionBinding
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.widgets.NotificationView
import com.phlox.tvwebbrowser.widgets.datedlist.DatedListAdapter
import com.phlox.tvwebbrowser.widgets.datedlist.DatedRow
import com.phlox.tvwebbrowser.widgets.datedlist.ListDividerDecoration
import kotlinx.coroutines.CoroutineScope

/**
 * The downloads page of the phone UI over the whole window, like the bookmarks
 * ([DownloadsNavigator.Look.PHONE]): the filters by the kind of the file in a row that scrolls sideways,
 * the downloads in progress first (with pause or resume and cancel), then the others by days. A tap opens
 * (installs) a file or downloads a failed one again, ⋮ opens the sheet of its actions. The folder button
 * opens Downloads in a file manager, ⋮ of the header has Clear all. Back ([backCallback]) closes it.
 */
class PhoneDownloadsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {
    private val vb = ViewPhoneDownloadsBinding.inflate(LayoutInflater.from(context), this)
    private lateinit var sheet: PhoneActionsSheet
    private lateinit var navigator: DownloadsNavigator
    private val text = DownloadRowText(context)
    private val adapter = Adapter()
    private var filters: List<Kind>? = null

    /** Enabled while the downloads are open */
    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
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
        vb.ibBack.setOnClickListener { hide() }
        vb.ibFolder.setOnClickListener { navigator.openFolder() }
        vb.ibMenu.setOnClickListener {
            sheet.show(null, null, listOf(PopupMenuView.Item(R.drawable.ic_clear_all, context.getString(R.string.clear_all)) {
                navigator.clearAll()
            }))
        }
    }

    fun attach(scope: CoroutineScope, sheet: PhoneActionsSheet, callback: DownloadsNavigator.ShellCallback) {
        this.sheet = sheet
        navigator = DownloadsNavigator(context, callback.downloads, callback.downloadCommands, callback.downloadDevice,
            scope, object : DownloadsNavigator.Host {
                override fun render(screen: Screen, focus: DownloadsNavigator.Focus?) = this@PhoneDownloadsView.render(screen, focus)

                //over the downloads while they are open
                override fun showMessage(icon: Int, message: String) {
                    if (isOpen) NotificationView.show(this@PhoneDownloadsView, icon, message) else callback.showMessage(icon, message)
                }

                override fun confirmClearAll(request: DownloadsNavigator.ClearAllRequest, onConfirm: (Boolean) -> Unit) =
                    callback.confirmClearAll(request, onConfirm)

                override fun showAll(focusId: Long?) = show(focusId)
            }, DownloadsNavigator.Look.PHONE)
    }

    fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        setPadding(left, top, right, 0)
        //the list scrolls under the navigation bar
        vb.rvItems.setPadding(0, 0, 0, bottom)
    }

    /** Opens the downloads, with the download [focusId] in view (else the top) */
    fun show(focusId: Long? = null) {
        filters = null
        navigator.start(focusId)
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
        sheet.hide()
        navigator.stop()
        backCallback.isEnabled = false
        animate().cancel()
        animate().alpha(0f).translationY(height * HIDDEN_SHIFT).setDuration(ANIMATION_DURATION)
            .withEndAction { visibility = GONE }.start()
    }

    private fun render(screen: Screen, focus: DownloadsNavigator.Focus?) {
        renderFilters(screen)
        vb.tvEmpty.isVisible = screen.items.isEmpty()
        adapter.submitItems(screen.items) {
            //a touch screen has no focus: the row to focus is scrolled into view
            val key = focus?.key ?: return@submitItems
            val position = (0 until adapter.itemCount).firstOrNull {
                (adapter.row(it) as? DatedRow.Item)?.value?.let { item -> DownloadsNavigator.keyOf(item.id) } == key
            } ?: return@submitItems
            (vb.rvItems.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, vb.rvItems.height / 3)
        }
    }

    /** All and the chips of the kinds there are files of */
    private fun renderFilters(screen: Screen) {
        if (filters != screen.filters) {
            filters = screen.filters
            vb.llFilters.removeAllViews()
            val inflater = LayoutInflater.from(context)
            val gap = resources.getDimensionPixelSize(R.dimen.ds_phone_chips_gap)
            for ((i, kind) in (listOf<Kind?>(null) + screen.filters).withIndex()) {
                val chip = inflater.inflate(R.layout.view_phone_download_chip, vb.llFilters, false) as TextView
                chip.setText(filterLabel(kind))
                chip.tag = DownloadsNavigator.filterKey(kind)
                chip.setOnClickListener { navigator.setFilter(kind) }
                vb.llFilters.addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = if (i > 0) gap else 0
                })
            }
        }
        vb.svFilters.isVisible = screen.filters.size > 1
        val chosen = DownloadsNavigator.filterKey(screen.filter)
        for (i in 0 until vb.llFilters.childCount) {
            val chip = vb.llFilters.getChildAt(i)
            chip.isActivated = chip.tag == chosen
        }
    }

    private fun showRowSheet(item: DownloadItem) {
        val items = navigator.actionsOf(item).map { action ->
            PopupMenuView.Item(action.icon, context.getString(action.label)) { navigator.perform(item, action) }
        }
        sheet.show(item.filename, text.caption(item, withHost = true, withDay = true), items)
    }

    private inner class Adapter : DatedListAdapter<DownloadItem>(DownloadItemDiff) {
        override fun timeOf(item: DownloadItem) = item.time

        override fun isPinned(item: DownloadItem) = item.isActive

        override fun headerTitle(day: Long): String = sectionTitle(context, day)

        override fun createHeaderViewHolder(parent: ViewGroup): HeaderViewHolder {
            val vb = ViewPhoneDownloadsSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return HeaderViewHolder(vb.root, vb.tvTitle, ::headerTitle)
        }

        override fun onCreateItemViewHolder(parent: ViewGroup): RecyclerView.ViewHolder =
            Holder(ViewPhoneDownloadRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindItem(holder: RecyclerView.ViewHolder, item: DownloadItem, payloads: List<Any>) =
            (holder as Holder).bind(item)
    }

    private inner class Holder(val vb: ViewPhoneDownloadRowBinding) : RecyclerView.ViewHolder(vb.root) {
        private var item: DownloadItem? = null
        private val views = DownloadRowViews(vb.ivIcon, vb.tvTitle, vb.tvCaption, vb.pbProgress,
            listOf(vb.ibFirst, vb.ibSecond), vb.ibOptions)

        init {
            vb.llMain.setOnClickListener { item?.let { navigator.onRowClick(it) } }
            vb.llMain.setOnLongClickListener {
                item?.let { showRowSheet(it) }
                true
            }
            vb.ibOptions.setOnClickListener { item?.let { showRowSheet(it) } }
        }

        fun bind(item: DownloadItem) {
            this.item = item
            bindDownloadRow(item, navigator, text, views, withHost = !item.isActive, withDay = false) { action ->
                navigator.perform(item, action)
            }
            //downloads in progress have their buttons on the row
            vb.ibOptions.isVisible = !item.isActive
        }
    }

    companion object {
        private const val HIDDEN_SHIFT = 0.05f
        private const val ANIMATION_DURATION = 200L
    }
}
