package com.phlox.tvwebbrowser.activity.main.view.tabs

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.activity.main.TabsModel
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.Listener
import com.phlox.tvwebbrowser.databinding.ViewTabsBinding
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.observable.ObservableList
import com.phlox.tvwebbrowser.widgets.CheckableContainer

class TabsView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {

  private var vb = ViewTabsBinding.inflate(LayoutInflater.from(context), this)

  private val tabsModel = ActiveModelsRepository.get(TabsModel::class, context as Activity)
  private val adapter: TabsAdapter = TabsAdapter(tabsModel)
  var current: Int by adapter::current
  var listener: Listener? by adapter::listener

  init {
    vb.rvTabs.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
    vb.btnAdd.setOnClickListener{
      listener?.onAddNewTabSelected()
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    tabsModel.tabsStates.subscribe(listChangeObserver)
    tabsModel.currentTab.subscribe(currentTabObserver)
    vb.rvTabs.adapter = adapter
  }

  override fun onDetachedFromWindow() {
    tabsModel.tabsStates.unsubscribe(listChangeObserver)
    tabsModel.currentTab.unsubscribe(currentTabObserver)
    super.onDetachedFromWindow()
  }

  override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
    if (gainFocus && childCount > 0) {
      for (i in 0 until vb.rvTabs.childCount) {
        val child = vb.rvTabs.getChildAt(i)

        if (child.tag is WebTabState) {
          val tab = child.tag
          val index = tabsModel.tabsStates.indexOf(tab)
          if (index == current && !child.hasFocus()) {
            child.requestFocus()
          }
        }
      }
    } else {
      super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }
  }

  fun onTabTitleUpdated(tab: WebTabState) {
    val tabIndex = tabsModel.tabsStates.indexOf(tab)
    adapter.notifyItemChanged(tabIndex)
  }

  fun onFavIconUpdated(tab: WebTabState) {
    val tabIndex = tabsModel.tabsStates.indexOf(tab)
    adapter.notifyItemChanged(tabIndex)
  }

  private val listChangeObserver: (ObservableList<WebTabState>) -> Unit = {
    adapter.onTabListChanged()
    vb.rvTabs.scrollToPosition(current)
  }

  private val currentTabObserver: (value: WebTabState?) -> Unit =  {
    //current = tabsModel.tabsStates.indexOf(it)
    //vb.rvTabs.scrollToPosition(current)
    //adapter.notifyDataSetChanged()
  }
}