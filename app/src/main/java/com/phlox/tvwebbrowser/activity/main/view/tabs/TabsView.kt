package com.phlox.tvwebbrowser.activity.main.view.tabs

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.Listener
import com.phlox.tvwebbrowser.browser.BrowserController
import com.phlox.tvwebbrowser.databinding.ViewTabsBinding
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.observable.ObservableList

/**
 * The tab strip of the TV bar: focusing a tab previews it, OK switches to it.
 */
class TabsView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

  private var vb = ViewTabsBinding.inflate(LayoutInflater.from(context), this)

  private lateinit var controller: BrowserController
  private val adapter: TabsAdapter = TabsAdapter(this)
  /** The place of the previewed tab, -1 if there is none */
  var current: Int
    get() = controller.tabsStates.indexOf(adapter.previewed)
    set(value) {
      adapter.previewed = controller.tabsStates.getOrNull(value)
    }
  var listener: Listener? by adapter::listener
  /** The tab shown as the current one */
  private var activeTab: BrowserTab? = null

  init {
      init()
  }

  fun init() {
    if (isInEditMode) return
    controller = ActiveModelsRepository.get(BrowserController::class, context)
    adapter.controller = controller
    vb.rvTabs.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
    //neighbour tabs share their border
    val overlap = resources.getDimensionPixelSize(R.dimen.ds_border_hairline)
    vb.rvTabs.addItemDecoration(object : RecyclerView.ItemDecoration() {
      override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.getChildAdapterPosition(view) > 0) outRect.left = -overlap
      }
    })
    vb.btnAdd.setOnClickListener{
      listener?.onAddNewTabSelected()
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (isInEditMode) return
    controller.tabsStates.subscribe(listChangeObserver)
    controller.currentTab.subscribe(currentTabObserver)
    vb.rvTabs.adapter = adapter
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    if (isInEditMode) return
    controller.tabsStates.unsubscribe(listChangeObserver)
    controller.currentTab.unsubscribe(currentTabObserver)
  }

  /**
   * Gives the focus to the tab (and so previews it). Before a tab is removed the focus moves to its
   * neighbour this way: the strip loses the focus if the focused view is removed.
   */
  fun focusTab(tab: BrowserTab) {
    val index = controller.tabsStates.indexOf(tab)
    if (index == -1) return
    val view = (0 until vb.rvTabs.childCount).map { vb.rvTabs.getChildAt(it) }.firstOrNull { it.tag === tab }
    if (view != null) {
      view.requestFocus()
    } else {
      vb.rvTabs.scrollToPosition(index)
      vb.rvTabs.post { focusTab(tab) }
    }
  }

  override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
    if (gainFocus && childCount > 0) {
      for (i in 0 until vb.rvTabs.childCount) {
        val child = vb.rvTabs.getChildAt(i)
        if (child.tag === adapter.previewed && !child.hasFocus()) {
          child.requestFocus()
        }
      }
    } else {
      super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }
  }

  /** Title or icon of the tab changed */
  fun onTabUpdated(tab: BrowserTab) {
    //the update is delivered asynchronously, the tab may be already closed
    val tabIndex = controller.tabsStates.indexOf(tab)
    if (tabIndex >= 0) {
      adapter.notifyItemChanged(tabIndex, TabsAdapter.PAYLOAD_UPDATE)
    }
  }

  /**
   * Scrolls the strip, if needed, to show the tab at [index] whole. The focused tab keeps its place when
   * tabs move, so a tab it traded places with may be pushed out of view.
   */
  fun scrollToShow(index: Int) {
    val lm = (vb.rvTabs.layoutManager as LinearLayoutManager)
    if (index in 0 until adapter.itemCount &&
      (index < lm.findFirstCompletelyVisibleItemPosition() || index > lm.findLastCompletelyVisibleItemPosition())) {
      vb.rvTabs.smoothScrollToPosition(index)
    }
  }

  /** The previewed tab becomes the current one again (the strip is left without switching) */
  fun resetPreview() {
    val index = controller.tabsStates.indexOf(controller.currentTab.value)
    if (index != -1) {
      current = index
      scrollToSeeCurrentTab()
    }
  }

  private fun scrollToSeeCurrentTab() {
    val lm = (vb.rvTabs.layoutManager as LinearLayoutManager)
    if (current < lm.findFirstCompletelyVisibleItemPosition() ||
      current > lm.findLastCompletelyVisibleItemPosition()
    ) {
      vb.rvTabs.scrollToPosition(current)
    }
  }

  private val listChangeObserver: (ObservableList<BrowserTab>) -> Unit = {
    adapter.onTabListChanged()

    scrollToSeeCurrentTab()
  }

  private val currentTabObserver: (value: BrowserTab?) -> Unit =  {
    //the previous and the new current tab change their look
    for (tab in listOf(activeTab, it)) {
      val index = controller.tabsStates.indexOf(tab)
      if (index != -1) adapter.notifyItemChanged(index, TabsAdapter.PAYLOAD_UPDATE)
    }
    activeTab = it
    if (it != null && it in controller.tabsStates && adapter.previewed != it) {
      adapter.previewed = it
      scrollToSeeCurrentTab()
    }
  }
}
