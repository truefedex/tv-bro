package com.phlox.tvwebbrowser.activity.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.databinding.ViewHistoryItemBinding
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.widgets.datedlist.DatedListAdapter
import com.phlox.tvwebbrowser.widgets.datedlist.DatedRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Created by fedex on 29.12.16.
 */

class HistoryAdapter(private val callback: Callback) : DatedListAdapter<HistoryItem>(ItemDiff) {
    interface Callback {
        fun onItemClick(item: HistoryItem)
        fun onItemLongClick(item: HistoryItem): Boolean
    }

    private val selectedIds = HashSet<Long>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    var isMultiselectMode = false
        set(value) {
            field = value
            if (!value) selectedIds.clear()
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        }

    val selectedItems: List<HistoryItem>
        get() = items.filter { it.id in selectedIds }

    fun isSelected(item: HistoryItem) = item.id in selectedIds

    fun setSelected(item: HistoryItem, selected: Boolean) {
        if (selected) selectedIds.add(item.id) else selectedIds.remove(item.id)
        val position = currentList.indexOfFirst { it is DatedRow.Item && it.value === item }
        if (position >= 0) notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    fun remove(removed: List<HistoryItem>, onCommitted: (() -> Unit)? = null) {
        val ids = removed.mapTo(HashSet()) { it.id }
        selectedIds.removeAll(ids)
        submitItems(items.filter { it.id !in ids }, onCommitted)
    }

    override fun timeOf(item: HistoryItem) = item.time

    override fun onCreateItemViewHolder(parent: ViewGroup): RecyclerView.ViewHolder =
        ItemViewHolder(ViewHistoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindItem(holder: RecyclerView.ViewHolder, item: HistoryItem, payloads: List<Any>) {
        holder as ItemViewHolder
        holder.item = item
        val vb = holder.vb
        if (PAYLOAD_SELECTION !in payloads) {
            vb.tvTitle.text = item.title
            vb.tvURL.text = item.url
            vb.tvTime.text = timeFormat.format(Date(item.time))
        }
        vb.cbSelection.visibility = if (isMultiselectMode) View.VISIBLE else View.GONE
        vb.cbSelection.isChecked = isSelected(item)
    }

    inner class ItemViewHolder(val vb: ViewHistoryItemBinding) : RecyclerView.ViewHolder(vb.root) {
        var item: HistoryItem? = null

        init {
            vb.root.setOnClickListener { item?.let { callback.onItemClick(it) } }
            vb.root.setOnLongClickListener { item?.let { callback.onItemLongClick(it) } ?: false }
        }
    }

    private object ItemDiff : DiffUtil.ItemCallback<HistoryItem>() {
        override fun areItemsTheSame(oldItem: HistoryItem, newItem: HistoryItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: HistoryItem, newItem: HistoryItem) =
            oldItem.time == newItem.time && oldItem.title == newItem.title && oldItem.url == newItem.url
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"
    }
}
