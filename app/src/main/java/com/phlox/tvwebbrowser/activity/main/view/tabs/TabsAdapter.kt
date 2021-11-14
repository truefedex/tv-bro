package com.phlox.tvwebbrowser.activity.main.view.tabs

import android.R.attr
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.TabsModel
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.TabViewHolder
import com.phlox.tvwebbrowser.databinding.ViewHorizontalWebtabItemBinding
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.widgets.CheckableContainer

class TabsAdapter(private val tabsModel: TabsModel) : RecyclerView.Adapter<TabViewHolder>() {
  val tabsCopy = ArrayList<WebTabState>().apply { addAll(tabsModel.tabsStates) }
  var current: Int = 0
    set(value) {
      field = value

    }
  var listener: Listener? = null

  interface Listener {
    fun onTitleChanged(index: Int)
    fun onTitleSelected(index: Int)
    fun onTitleOptions(index: Int)
    fun onAddNewTabSelected()
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
    tabsCopy.apply { clear() }.addAll(tabsModel.tabsStates)
    notifyDataSetChanged()
  }

  inner class TabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val vb = ViewHorizontalWebtabItemBinding.bind(itemView)

    fun bind(tabState: WebTabState, position: Int) {
      vb.tvTitle.text = tabState.title
      if (tabState.faviconHash != null && tabState.favicon == null) {
        tabState.loadFavicon(itemView.context)
      }
      val favIcon = tabState.favicon
      if (favIcon != null)
        vb.ivFavicon.setImageBitmap(favIcon)
      else
        vb.ivFavicon.setImageResource(R.drawable.ic_launcher)

      vb.root.isChecked = position == current
      //val padding = if (position == current) 0 else itemView.context.resources.getDimensionPixelSize(R.dimen.web_tab_padding)
      //vb.root.setPadding(padding, 0, padding, 0)


      vb.root.tag = tabState

      vb.root.setOnFocusChangeListener { v, hasFocus ->
        if (hasFocus) {
          if (current != position) {
            val oldCurrent = current
            current = position
            listener?.onTitleChanged(position)
            notifyItemChanged(oldCurrent)
            notifyItemChanged(current)
          }
        }
      }

      vb.root.setOnClickListener {
        listener?.onTitleSelected(position)
      }
      vb.root.setOnLongClickListener {
        listener?.onTitleOptions(position)
        true
      }
    }
  }
}