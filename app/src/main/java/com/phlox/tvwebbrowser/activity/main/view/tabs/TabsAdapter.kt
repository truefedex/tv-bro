package com.phlox.tvwebbrowser.activity.main.view.tabs

import android.graphics.Outline
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.TabViewHolder
import com.phlox.tvwebbrowser.browser.BrowserController
import com.phlox.tvwebbrowser.databinding.ViewHorizontalWebtabItemBinding
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class TabsAdapter(private val tabsView: TabsView) : RecyclerView.Adapter<TabViewHolder>() {
    companion object {
        /**
         * For changes of a tab's look: with a payload the item animator keeps the view of the tab,
         * so a focused tab keeps the focus
         */
        const val PAYLOAD_UPDATE = "update"
    }

    var controller: BrowserController? = null
        set(value) {
            field = value
            onTabListChanged()
        }
    private val tabsCopy =
        ArrayList<BrowserTab>().apply { addAll(controller?.tabsStates ?: emptyList()) }
    /**
     * The previewed tab: the focused one, the current one when the strip is not focused.
     * The tab and not its place, which changes when tabs are closed or moved.
     */
    var previewed: BrowserTab? = null
    var listener: Listener? = null

    interface Listener {
        fun onTitleChanged(index: Int)
        fun onTitleSelected(index: Int)
        fun onAddNewTabSelected()
        /** Down, the Menu key or a long press of OK on the tab, its [view] in the strip */
        fun showTabMenu(tab: BrowserTab, view: View)
    }

    init {
        //tabs keep their views when the list changes, so the focus stays on its tab
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = System.identityHashCode(tabsCopy[position]).toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_horizontal_webtab_item, parent, false)
        return TabViewHolder(view)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(tabsCopy[position])
    }

    override fun getItemCount(): Int {
        return tabsCopy.size
    }

    fun onTabListChanged() {
        val tabsDiffUtilCallback =
            TabsDiffUtillCallback(tabsCopy, controller?.tabsStates ?: emptyList())
        val tabsDiffResult = DiffUtil.calculateDiff(tabsDiffUtilCallback)
        tabsCopy.apply { clear() }.addAll(controller?.tabsStates ?: emptyList())
        tabsDiffResult.dispatchUpdatesTo(this)
    }

    inner class TabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val vb = ViewHorizontalWebtabItemBinding.bind(itemView)

        init {
            vb.ivFavicon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            vb.ivFavicon.clipToOutline = true
        }

        fun bind(tabState: BrowserTab) {
            vb.root.tag = tabState

            vb.tvTitle.text = tabState.title
            //the current tab looks selected; the focused one has the focus look
            vb.root.isActivated = tabState == controller?.currentTab?.value

            vb.ivFavicon.setImageResource(R.mipmap.ic_launcher)

            val url = tabState.url
            if (url != Config.HOME_PAGE_URL && url != Config.HOME_URL_ALIAS) {
                val scope = (itemView.activity as AppCompatActivity).lifecycleScope
                scope.launch(Dispatchers.Main) {
                    val favicon = FaviconsPool.get(url)
                    val ts = vb.root.tag as BrowserTab
                    if (url != ts.url) return@launch //url was changed while loading favicon
                    if (!itemView.isAttachedToWindow) return@launch
                    favicon?.let {
                        vb.ivFavicon.setImageBitmap(it)
                    } ?: run {
                        vb.ivFavicon.setImageResource(R.mipmap.ic_launcher)
                    }
                }
            }

            //focusing a tab previews it
            vb.root.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus && previewed != tabState) {
                    previewed = tabState
                    listener?.onTitleChanged(tabState.position)
                }
            }

            //OK switches to it
            vb.root.setOnClickListener {
                listener?.onTitleSelected(tabState.position)
            }

            vb.root.setOnLongClickListener {
                listener?.showTabMenu(tabState, vb.root)
                true
            }

            vb.root.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN && keyCode != KeyEvent.KEYCODE_MENU) {
                    return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    listener?.showTabMenu(tabState, vb.root)
                }
                true
            }
        }
    }
}
