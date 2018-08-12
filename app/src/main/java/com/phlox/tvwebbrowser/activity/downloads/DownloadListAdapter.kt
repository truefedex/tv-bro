package com.phlox.tvwebbrowser.activity.downloads

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter

import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.Utils

import java.util.ArrayList

import de.halfbit.pinnedsection.PinnedSectionListView

/**
 * Created by PDT on 24.01.2017.
 */

class DownloadListAdapter(private val downloadsActivity: DownloadsActivity) : BaseAdapter(), PinnedSectionListView.PinnedSectionListAdapter {
    private val downloads = ArrayList<Download>()
    private var lastHeaderDate: Long = -1
    var realCount: Long = 0
        private set

    fun addItems(items: List<Download>) {
        if (items.isEmpty()) {
            return
        }
        for (download in items) {
            if (!Utils.isSameDate(download.time, lastHeaderDate)) {
                lastHeaderDate = download.time
                this.downloads.add(Download.createDateHeaderInfo(download.time))
            }
            this.downloads.add(download)
            realCount++
        }
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return downloads.size
    }

    override fun getItem(i: Int): Any {
        return downloads[i]
    }

    override fun getItemId(i: Int): Long {
        return i.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val hiv = if (convertView != null) {
            convertView as DownloadListItemView
        } else {
            DownloadListItemView(downloadsActivity, getItemViewType(position))
        }
        hiv.download = downloads[position]
        return hiv
    }

    override fun getViewTypeCount(): Int {
        return 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (downloads[position].isDateHeader) VIEW_TYPE_HEADER else VIEW_TYPE_DOWNLOAD_ITEM
    }

    override fun isItemViewTypePinned(viewType: Int): Boolean {
        return viewType == VIEW_TYPE_HEADER
    }

    fun remove(download: Download) {
        downloads.remove(download)
        notifyDataSetChanged()
    }

    companion object {
        val VIEW_TYPE_DOWNLOAD_ITEM = 0
        val VIEW_TYPE_HEADER = 1
    }
}
