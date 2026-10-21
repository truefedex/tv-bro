package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.PageVideoRowText
import com.phlox.tvwebbrowser.databinding.ViewPageVideoRowBinding
import com.phlox.tvwebbrowser.databinding.ViewPageVideosPanelBinding
import com.phlox.tvwebbrowser.utils.ExternalVideoPlayer
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.widgets.datedlist.ListDividerDecoration

/**
 * The TV panel of the videos of the page that other apps can play, under the Videos button of the bar,
 * over the page; the page keeps playing them as before. OK on a row plays the video in the built-in player;
 * the options of a row (its button, the Menu key and a long OK) also open it in another app, copy its address
 * and download a file. The list follows the page while the panel is open. Back closes the panel; the button
 * gets the focus back.
 */
class VideosPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AnchoredOverlayView(context, attrs) {
    interface Callback {
        /** In the built-in player */
        fun play(video: PageVideo)
        fun openInOtherApp(video: PageVideo)
        fun copyLink(video: PageVideo)
        /** Only files: streams are no files to download */
        fun download(video: PageVideo)
    }

    private lateinit var menu: PopupMenuView
    private lateinit var callback: Callback
    private val vb = ViewPageVideosPanelBinding.inflate(LayoutInflater.from(context), this)
    override val panel: View get() = vb.llPanel
    private val adapter = Adapter()

    init {
        vb.llPanel.clipToOutline = true
        vb.rvItems.layoutManager = LinearLayoutManager(context)
        vb.rvItems.itemAnimator = null
        vb.rvItems.adapter = adapter
        vb.rvItems.addItemDecoration(ListDividerDecoration(vb.rvItems, adapter, R.attr.tbColorDsLine, R.dimen.ds_border_hairline))
    }

    fun attach(menu: PopupMenuView, callback: Callback) {
        this.menu = menu
        this.callback = callback
    }

    /** The videos of the page now */
    fun render(videos: List<PageVideo>) {
        val hadFocus = vb.tvEmpty.hasFocus()
        vb.tvEmpty.isVisible = videos.isEmpty()
        vb.rvItems.isVisible = videos.isNotEmpty()
        adapter.submitList(videos) {
            if (!isOpen) return@submitList
            //the focus stays in the panel when the videos come or go
            when {
                videos.isEmpty() && !vb.tvEmpty.hasFocus() && !menu.isOpen -> vb.tvEmpty.requestFocus()
                videos.isNotEmpty() && hadFocus -> vb.rvItems.post { vb.rvItems.getChildAt(0)?.findViewById<View>(R.id.llMain)?.requestFocus() }
            }
        }
    }

    /** Opens the panel under the [anchor] (the Videos button), its end under the end of [endAlignTo] */
    fun show(anchor: View, endAlignTo: View) {
        if (isOpen) return
        anchor.isActivated = true
        showUnder(anchor, resources.getDimensionPixelSize(R.dimen.ds_site_panel_gap),
            if (adapter.itemCount == 0) vb.tvEmpty else vb.rvItems, endAlignTo,
            resources.getDimensionPixelSize(R.dimen.ds_bookmarks_panel_margin_bottom))
    }

    override fun onHidden() {
        menu.hide()
        anchor?.isActivated = false
    }

    private fun showRowMenu(video: PageVideo, anchor: View) {
        val items = mutableListOf(
            PopupMenuView.Item(R.drawable.ic_play, context.getString(R.string.play_in_tv_bro)) { callback.play(video) },
            PopupMenuView.Item(R.drawable.ic_open_in_new, context.getString(R.string.open_in_external_application)) { callback.openInOtherApp(video) },
            PopupMenuView.Item(R.drawable.ic_link, context.getString(R.string.copy_link)) { callback.copyLink(video) })
        if (video.kind == PageVideo.Kind.FILE) {
            items += PopupMenuView.Item(R.drawable.ic_downloads, context.getString(R.string.download)) { callback.download(video) }
        }
        menu.show(anchor, items, alignEnd = true)
    }

    private inner class Adapter : ListAdapter<PageVideo, Holder>(Diff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ViewPageVideoRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }

    private inner class Holder(val vb: ViewPageVideoRowBinding) : RecyclerView.ViewHolder(vb.root) {
        private var video: PageVideo? = null

        init {
            vb.llMain.setOnClickListener { video?.let { callback.play(it) } }
            vb.llMain.setOnLongClickListener {
                video?.let { showRowMenu(it, vb.ibOptions) }
                true
            }
            //the Menu key opens the options from the row and from its button
            val menuKey = View.OnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_MENU) return@OnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) video?.let { showRowMenu(it, vb.ibOptions) }
                true
            }
            vb.llMain.setOnKeyListener(menuKey)
            vb.ibOptions.setOnKeyListener(menuKey)
            vb.ibOptions.setOnClickListener { video?.let { showRowMenu(it, vb.ibOptions) } }
        }

        fun bind(video: PageVideo) {
            this.video = video
            val title = ExternalVideoPlayer.title(video)
            vb.tvTitle.text = title
            vb.tvCaption.text = PageVideoRowText.caption(video)
            vb.ibOptions.contentDescription = context.getString(R.string.options_of, title)
        }
    }

    private object Diff : DiffUtil.ItemCallback<PageVideo>() {
        override fun areItemsTheSame(oldItem: PageVideo, newItem: PageVideo) = oldItem.url == newItem.url
        override fun areContentsTheSame(oldItem: PageVideo, newItem: PageVideo) = oldItem == newItem
    }
}
