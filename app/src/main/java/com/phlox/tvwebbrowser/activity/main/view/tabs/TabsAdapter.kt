package com.phlox.tvwebbrowser.activity.main.view.tabs

import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.TabsModel
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.TabViewHolder
import com.phlox.tvwebbrowser.databinding.ViewHorizontalWebtabItemBinding
import com.phlox.tvwebbrowser.model.WebTabState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DiffUtil.DiffResult


class TabsAdapter(private val tabsModel: TabsModel, private val tabsView: TabsView) : RecyclerView.Adapter<TabViewHolder>() {
  private val tabsCopy = ArrayList<WebTabState>().apply { addAll(tabsModel.tabsStates) }
  var current: Int = 0
  var listener: Listener? = null
  val uiHandler = Handler(Looper.getMainLooper())

  interface Listener {
    fun onTitleChanged(index: Int)
    fun onTitleSelected(index: Int)
    fun onAddNewTabSelected()
    fun closeTab(tabState: WebTabState?)
    fun openInNewTab(url: String, tabIndex: Int)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
    val view = LayoutInflater.from(parent.context).inflate(R.layout.view_horizontal_webtab_item, parent, false)
    return TabViewHolder(view)
  }

  override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
    holder.bind(tabsCopy[position], position)
  }

  override fun getItemCount(): Int {
    return tabsCopy.size
  }

  fun onTabListChanged() {
    val tabsDiffUtilCallback = TabsDiffUtillCallback(tabsCopy, tabsModel.tabsStates)
    val tabsDiffResult = DiffUtil.calculateDiff(tabsDiffUtilCallback)
    tabsCopy.apply { clear() }.addAll(tabsModel.tabsStates)
    tabsDiffResult.dispatchUpdatesTo(this)
  }

  inner class TabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val vb = ViewHorizontalWebtabItemBinding.bind(itemView)

    fun bind(tabState: WebTabState, position: Int) {
      vb.tvTitle.text = tabState.title
      if (tabState.faviconHash != null && tabState.favicon == null) {
        tabState.loadFavicon(itemView.context)
      }
      val favIcon = tabState.favicon
      if (favIcon != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          val drawable = BitmapDrawable(itemView.context.resources, favIcon)
          drawable.isFilterBitmap = false
          vb.ivFavicon.setImageDrawable(drawable)
        } else {
          vb.ivFavicon.setImageBitmap(favIcon)
        }
      } else
        vb.ivFavicon.setImageResource(R.drawable.ic_launcher)

      vb.root.isChecked = position == current

      vb.root.tag = tabState

      vb.root.setOnFocusChangeListener { v, hasFocus ->
        if (hasFocus) {
          if (current != tabState.position) {
            val oldCurrent = current
            current = tabState.position
            listener?.onTitleChanged(position)
            uiHandler.post {
              notifyItemChanged(oldCurrent)
              notifyItemChanged(current)
            }
          }
        }
      }

      vb.root.setOnClickListener {
        listener?.onTitleSelected(tabState.position)
      }
      vb.root.setOnLongClickListener {
        tabsView.showTabOptions(tabState)
        true
      }
    }
  }
}