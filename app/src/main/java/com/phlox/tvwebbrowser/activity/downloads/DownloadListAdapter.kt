package com.phlox.tvwebbrowser.activity.downloads

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.widgets.datedlist.DatedListAdapter

/**
 * Created by PDT on 24.01.2017.
 */

class DownloadListAdapter(private val downloadsActivity: DownloadsActivity) : DatedListAdapter<Download>(ItemDiff) {

    override fun timeOf(item: Download) = item.time

    override fun onCreateItemViewHolder(parent: ViewGroup): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.view_download_item, parent, false) as DownloadListItemView
        view.updates = downloadsActivity
        view.setOnClickListener { downloadsActivity.onItemClick(view) }
        view.setOnLongClickListener { downloadsActivity.onItemLongClick(view) }
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindItem(holder: RecyclerView.ViewHolder, item: Download, payloads: List<Any>) {
        (holder.itemView as DownloadListItemView).download = item
    }

    private object ItemDiff : DiffUtil.ItemCallback<Download>() {
        override fun areItemsTheSame(oldItem: Download, newItem: Download) = oldItem.id == newItem.id
        //the progress of a download comes to its row directly (see DownloadListItemView)
        override fun areContentsTheSame(oldItem: Download, newItem: Download) = oldItem === newItem
    }
}
