package com.phlox.tvwebbrowser.activity.main.view.tabs

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.TabsModel
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.TabViewHolder
import com.phlox.tvwebbrowser.databinding.ViewHorizontalWebtabItemBinding
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.activity
import com.phlox.tvwebbrowser.widgets.CheckableContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class TabsAdapter(private val tabsView: TabsView) : RecyclerView.Adapter<TabViewHolder>() {
    private val tabsCopy =
        ArrayList<WebTabState>().apply { addAll(tabsModel?.tabsStates ?: emptyList()) }
    var current: Int = 0
    var listener: Listener? = null
    val uiHandler = Handler(Looper.getMainLooper())
    var checkedView: CheckableContainer? = null
    var tabsModel: TabsModel? = null
        set(value) {
            field = value
            onTabListChanged()
        }

    interface Listener {
        fun onTitleChanged(index: Int)
        fun onTitleSelected(index: Int)
        fun onAddNewTabSelected()
        fun closeTab(tabState: WebTabState?)
        fun openInNewTab(url: String, tabIndex: Int)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_horizontal_webtab_item, parent, false)
        return TabViewHolder(view)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(tabsCopy[position], position)
    }

    override fun getItemCount(): Int {
        return tabsCopy.size
    }

    fun onTabListChanged() {
        val tabsDiffUtilCallback =
            TabsDiffUtillCallback(tabsCopy, tabsModel?.tabsStates ?: emptyList())
        val tabsDiffResult = DiffUtil.calculateDiff(tabsDiffUtilCallback)
        tabsCopy.apply { clear() }.addAll(tabsModel?.tabsStates ?: emptyList())
        tabsDiffResult.dispatchUpdatesTo(this)
    }

    inner class TabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val vb = ViewHorizontalWebtabItemBinding.bind(itemView)

        fun bind(tabState: WebTabState, position: Int) {
            vb.root.tag = tabState

            vb.tvTitle.text = tabState.title

            if (current == tabState.position) {
                checkedView?.isChecked = false
                vb.root.isChecked = true
                checkedView = vb.root
            }

            vb.ivFavicon.setImageResource(R.drawable.ic_launcher)

            val url = tabState.url
            if (url != Config.DEFAULT_HOME_URL) {
                val scope = (itemView.activity as AppCompatActivity).lifecycleScope
                scope.launch(Dispatchers.Main) {
                    val favicon = FaviconsPool.get(url)
                    val ts = vb.root.tag as WebTabState
                    if (url != ts.url) return@launch //url was changed while loading favicon
                    if (!itemView.isAttachedToWindow) return@launch
                    favicon?.let {
                        vb.ivFavicon.setImageBitmap(it)
                    } ?: run {
                        vb.ivFavicon.setImageResource(R.drawable.ic_launcher)
                    }
                }
            }

            vb.root.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    if (current != tabState.position) {
                        current = tabState.position
                        listener?.onTitleChanged(position)
                        checkedView?.isChecked = false
                        vb.root.isChecked = true
                        checkedView = vb.root
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