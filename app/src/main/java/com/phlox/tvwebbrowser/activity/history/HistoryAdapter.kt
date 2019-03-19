package com.phlox.tvwebbrowser.activity.history

import android.util.MutableBoolean
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter

import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.utils.Utils

import java.util.ArrayList

import de.halfbit.pinnedsection.PinnedSectionListView

/**
 * Created by fedex on 29.12.16.
 */

class HistoryAdapter : BaseAdapter(), PinnedSectionListView.PinnedSectionListAdapter {
    val items = ArrayList<HistoryItem>()
    private var lastHeaderDate: Long = -1
    var realCount: Long = 0
        private set
    var isMultiselectMode = false
        set(multiselectMode) {
            field = multiselectMode
            if (!multiselectMode) {
                for (hi in items) {
                    hi.selected = false
                }
            }
            notifyDataSetChanged()
        }
    private val _tmpSelected = ArrayList<HistoryItem>()

    val selectedItems: List<HistoryItem>
        get() {
            _tmpSelected.clear()
            for (hi in items) {
                if (hi.selected) {
                    _tmpSelected.add(hi)
                }
            }
            return _tmpSelected
        }

    fun addItems(items: List<HistoryItem>) {
        if (items.isEmpty()) {
            return
        }
        for (hi in items) {
            if (!Utils.isSameDate(hi.time, lastHeaderDate)) {
                lastHeaderDate = hi.time
                this.items.add(HistoryItem.createDateHeaderInfo(hi.time))
            }
            this.items.add(hi)
            realCount++
        }
        notifyDataSetChanged()
    }

    override fun getCount(): Int {
        return items.size
    }

    override fun getItem(position: Int): Any {
        return items[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val hiv: HistoryItemView
        if (convertView != null) {
            hiv = convertView as HistoryItemView
        } else {
            hiv = HistoryItemView(parent.context, getItemViewType(position))
        }
        hiv.setHistoryItem(items[position], isMultiselectMode)
        return hiv
    }

    override fun getViewTypeCount(): Int {
        return 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isDateHeader) VIEW_TYPE_HEADER else VIEW_TYPE_HISTORY_ITEM
    }

    override fun isItemViewTypePinned(viewType: Int): Boolean {
        return viewType == VIEW_TYPE_HEADER
    }

    fun erase() {
        items.clear()
        notifyDataSetChanged()
    }

    fun remove(historyItem: HistoryItem) {
        items.remove(historyItem)
        notifyDataSetChanged()
    }

    fun remove(selectedItems: List<HistoryItem>) {
        items.removeAll(selectedItems)
        notifyDataSetChanged()
    }

    companion object {
        val VIEW_TYPE_HISTORY_ITEM = 0
        val VIEW_TYPE_HEADER = 1
    }
}
