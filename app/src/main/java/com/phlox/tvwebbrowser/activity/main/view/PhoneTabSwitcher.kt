package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.BrowserController
import com.phlox.tvwebbrowser.databinding.ViewPhoneTabCardBinding
import com.phlox.tvwebbrowser.databinding.ViewPhoneTabSwitcherBinding
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.activity
import com.phlox.tvwebbrowser.utils.observable.ListChangeObserver
import com.phlox.tvwebbrowser.utils.observable.ValueObserver
import com.phlox.tvwebbrowser.utils.themeColor
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The tab switcher of the phone UI over the whole window: the tabs as cards in a grid (the current one
 * selected), the incognito switch, and Close all, New tab and Done. A tap on a card switches to its tab,
 * its × or a swipe closes it. Back ([backCallback]) or Done closes the switcher.
 */
class PhoneTabSwitcher @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    interface Callback {
        fun selectTab(tab: BrowserTab)
        fun closeTab(tab: BrowserTab)
        fun closeAllTabs()
        fun openNewTab()
        fun toggleIncognitoMode()
    }

    var callback: Callback? = null
    private lateinit var controller: BrowserController
    private val vb: ViewPhoneTabSwitcherBinding
    private val adapter = CardsAdapter()
    private val layoutManager = GridLayoutManager(context, 2)
    private val rowHeight = resources.getDimensionPixelSize(R.dimen.ds_phone_switcher_row_height)
    private val minCardWidth = resources.getDimensionPixelSize(R.dimen.ds_phone_tab_card_min_width)

    /** Enabled while the switcher is open */
    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
    }

    val isOpen: Boolean get() = isVisible

    private val tabsObserver: ListChangeObserver<BrowserTab> = { tabs ->
        adapter.submit(tabs)
        vb.tvTitle.text = resources.getQuantityString(R.plurals.tab_count, tabs.size, tabs.size)
    }

    private val currentTabObserver: ValueObserver<BrowserTab?> = { adapter.updateCurrent() }

    private val swipeToClose = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val tab = (viewHolder as CardHolder).tab ?: return
            callback?.closeTab(tab)
        }

        override fun onChildDraw(c: Canvas, rv: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                                 dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
            super.onChildDraw(c, rv, viewHolder, dX, dY, actionState, isCurrentlyActive)
            //the card fades out as it goes
            viewHolder.itemView.alpha = 1f - (abs(dX) / viewHolder.itemView.width).coerceAtMost(1f)
        }

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            viewHolder.itemView.alpha = 1f
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(context.themeColor(R.attr.tbColorDsBgPage))
        //touches do not go through to the page
        isClickable = true
        vb = ViewPhoneTabSwitcherBinding.inflate(LayoutInflater.from(context), this)
        visibility = GONE

        val gap = resources.getDimensionPixelSize(R.dimen.ds_phone_switcher_grid_gap)
        val padding = resources.getDimensionPixelSize(R.dimen.ds_phone_switcher_padding)
        //each card has half of the gap around it
        vb.rvTabs.setPadding(padding - gap / 2, 0, padding - gap / 2, padding - gap / 2)
        vb.rvTabs.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.set(gap / 2, gap / 2, gap / 2, gap / 2)
            }
        })
        vb.rvTabs.layoutManager = layoutManager
        vb.rvTabs.adapter = adapter
        ItemTouchHelper(swipeToClose).attachToRecyclerView(vb.rvTabs)

        vb.ibIncognito.isActivated = AppContext.provideConfig().incognitoMode
        vb.ibIncognito.setOnClickListener { callback?.toggleIncognitoMode() }
        vb.btnCloseAll.setOnClickListener { callback?.closeAllTabs() }
        vb.llNewTab.setOnClickListener { callback?.openNewTab() }
        vb.btnDone.setOnClickListener { hide() }
    }

    fun bind(controller: BrowserController) {
        this.controller = controller
    }

    /** The cards keep out of the system bars; the bottom row reaches down under the navigation bar */
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        setPadding(left, top, right, 0)
        val row = vb.llBottomRow
        row.setPadding(row.paddingLeft, row.paddingTop, row.paddingRight, bottom)
        row.layoutParams = row.layoutParams.apply { height = rowHeight + bottom }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        //more columns in landscape
        val width = w - paddingLeft - paddingRight - vb.rvTabs.paddingLeft - vb.rvTabs.paddingRight
        val spans = (width / minCardWidth).coerceAtLeast(2)
        if (layoutManager.spanCount != spans) {
            post { layoutManager.spanCount = spans }
        }
    }

    fun show() {
        if (backCallback.isEnabled) return
        animate().cancel()
        backCallback.isEnabled = true
        controller.tabsStates.subscribe(tabsObserver)
        controller.currentTab.subscribe(currentTabObserver, notifyOnSubscribe = false)
        //titles and pictures have changed while the switcher was closed
        adapter.rebindAll()
        val current = controller.currentTab.value
        val index = controller.tabsStates.indexOf(current)
        if (index != -1) layoutManager.scrollToPosition(index)
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(ANIMATION_DURATION).start()
        //the card of the current tab shows the page as it is now
        if (current != null) {
            scope.launch {
                current.webEngine.renderThumbnail(current.thumbnail)?.let { current.updateThumbnail(it) }
                adapter.updateThumbnail(current)
            }
        }
    }

    fun hide() {
        if (!isOpen || !backCallback.isEnabled) return
        backCallback.isEnabled = false
        controller.tabsStates.unsubscribe(tabsObserver)
        controller.currentTab.unsubscribe(currentTabObserver)
        animate().alpha(0f).setDuration(ANIMATION_DURATION).withEndAction { visibility = GONE }.start()
    }

    private val scope get() = (activity as AppCompatActivity).lifecycleScope

    private inner class CardsAdapter : RecyclerView.Adapter<CardHolder>() {
        private val tabs = ArrayList<BrowserTab>()

        init {
            //tabs keep their cards when the list changes
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = System.identityHashCode(tabs[position]).toLong()

        override fun getItemCount() = tabs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CardHolder(ViewPhoneTabCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.bind(tabs[position])

        override fun onBindViewHolder(holder: CardHolder, position: Int, payloads: MutableList<Any>) {
            when {
                payloads.isEmpty() -> holder.bind(tabs[position])
                PAYLOAD_THUMBNAIL in payloads -> holder.loadThumbnail()
                else -> holder.updateCurrent()
            }
        }

        fun submit(newTabs: List<BrowserTab>) {
            val old = ArrayList(tabs)
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newTabs.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos] === newTabs[newPos]
                override fun areContentsTheSame(oldPos: Int, newPos: Int) = true
            })
            tabs.clear()
            tabs.addAll(newTabs)
            diff.dispatchUpdatesTo(this)
            updateCurrent()
        }

        fun updateCurrent() = notifyItemRangeChanged(0, tabs.size, PAYLOAD_CURRENT)

        fun rebindAll() = notifyItemRangeChanged(0, tabs.size)

        fun updateThumbnail(tab: BrowserTab) {
            val index = tabs.indexOf(tab)
            if (index != -1) notifyItemChanged(index, PAYLOAD_THUMBNAIL)
        }
    }

    private inner class CardHolder(val vb: ViewPhoneTabCardBinding) : RecyclerView.ViewHolder(vb.root) {
        var tab: BrowserTab? = null

        init {
            vb.ivFavicon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            vb.ivFavicon.clipToOutline = true
            //the rounded corners of the card cut the picture
            vb.root.clipToOutline = true
            vb.ivThumbnail.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> cropToTop(vb.ivThumbnail) }
            vb.root.setOnClickListener { tab?.let { callback?.selectTab(it) } }
            vb.ibClose.setOnClickListener { tab?.let { callback?.closeTab(it) } }
        }

        fun bind(tab: BrowserTab) {
            this.tab = tab
            itemView.alpha = 1f
            vb.tvTitle.text = tab.title.ifEmpty { tab.url }
            updateCurrent()
            loadFavicon(tab)
            loadThumbnail()
        }

        fun updateCurrent() {
            vb.root.isActivated = tab != null && tab == controller.currentTab.value
        }

        private fun loadFavicon(tab: BrowserTab) {
            vb.ivFavicon.setImageResource(R.mipmap.ic_launcher)
            val url = tab.url
            if (url == Config.HOME_PAGE_URL || url == Config.HOME_URL_ALIAS) return
            scope.launch {
                val favicon = FaviconsPool.get(url) ?: return@launch
                //the card may show another tab by now
                if (this@CardHolder.tab === tab && tab.url == url) vb.ivFavicon.setImageBitmap(favicon)
            }
        }

        fun loadThumbnail() {
            val tab = tab ?: return
            vb.ivThumbnail.setImageBitmap(tab.thumbnail)
            cropToTop(vb.ivThumbnail)
            if (tab.thumbnail != null) return
            scope.launch {
                val thumbnail = controller.loadThumbnail(tab) ?: return@launch
                if (this@CardHolder.tab === tab) {
                    vb.ivThumbnail.setImageBitmap(thumbnail)
                    cropToTop(vb.ivThumbnail)
                }
            }
        }
    }

    companion object {
        private const val ANIMATION_DURATION = 150L
        private const val PAYLOAD_CURRENT = "current"
        private const val PAYLOAD_THUMBNAIL = "thumbnail"

        /** Fills the view with the top of the picture (the top of the page) */
        private fun cropToTop(view: ImageView) {
            val drawable = view.drawable ?: return
            val viewWidth = view.width - view.paddingLeft - view.paddingRight
            val viewHeight = view.height - view.paddingTop - view.paddingBottom
            if (viewWidth <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
            val scale = maxOf(viewWidth.toFloat() / drawable.intrinsicWidth,
                viewHeight.toFloat() / drawable.intrinsicHeight)
            view.scaleType = ImageView.ScaleType.MATRIX
            view.imageMatrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((viewWidth - drawable.intrinsicWidth * scale) / 2f, 0f)
            }
        }
    }
}
