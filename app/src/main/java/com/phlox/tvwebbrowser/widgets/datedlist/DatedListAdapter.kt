package com.phlox.tvwebbrowser.widgets.datedlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.databinding.ViewDatedListHeaderBinding
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** A row of a list grouped by days: the header of a day or an item */
sealed class DatedRow<out T : Any> {
    /** [day] is the start of the day in the local time zone, or [PINNED_GROUP] */
    data class Header(val day: Long) : DatedRow<Nothing>()
    data class Item<T : Any>(val value: T) : DatedRow<T>()

    companion object {
        /** The [Header.day] of the group of items before the days (the downloads in progress) */
        const val PINNED_GROUP = Long.MAX_VALUE
    }
}

/**
 * The rows for [items] (sorted by time, the latest first): each day starts with its header. The items
 * that are [pinned] (they come first) are a group of their own before the days.
 */
fun <T : Any> buildDatedRows(items: List<T>, pinned: (T) -> Boolean = { false }, timeOf: (T) -> Long): List<DatedRow<T>> {
    val rows = ArrayList<DatedRow<T>>(items.size + 8)
    var lastDay = Long.MIN_VALUE
    for (item in items) {
        val day = if (pinned(item)) DatedRow.PINNED_GROUP else startOfDay(timeOf(item))
        if (day != lastDay) {
            rows.add(DatedRow.Header(day))
            lastDay = day
        }
        rows.add(DatedRow.Item(item))
    }
    return rows
}

/** For each row, the position of the header of its day (-1 for rows before the first header) */
fun headerPositions(rows: List<DatedRow<*>>): IntArray {
    var header = -1
    return IntArray(rows.size) { position ->
        if (rows[position] is DatedRow.Header) header = position
        header
    }
}

fun startOfDay(time: Long): Long = Calendar.getInstance().run {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

/**
 * An adapter of a list grouped by days with a header row for each day (see [StickyHeaderDecoration]
 * for the pinned header). Subclasses create and bind the item rows; the rows are diffed in the background.
 */
abstract class DatedListAdapter<T : Any>(itemDiff: DiffUtil.ItemCallback<T>) :
    ListAdapter<DatedRow<T>, RecyclerView.ViewHolder>(RowDiff(itemDiff)) {

    /** The items, the latest first (without headers) */
    var items: List<T> = emptyList()
        private set

    /** For each row, the position of the header of its day */
    private var headerPositions = IntArray(0)

    protected abstract fun timeOf(item: T): Long
    /** The items of the group before the days: they come first */
    protected open fun isPinned(item: T): Boolean = false
    protected abstract fun onCreateItemViewHolder(parent: ViewGroup): RecyclerView.ViewHolder
    protected abstract fun onBindItem(holder: RecyclerView.ViewHolder, item: T, payloads: List<Any>)

    /** Shows [items]; [onCommitted] runs when the rows are in the adapter (the diff is computed in the background) */
    fun submitItems(items: List<T>, onCommitted: (() -> Unit)? = null) {
        this.items = items
        submitList(buildDatedRows(items, ::isPinned, ::timeOf), onCommitted?.let { Runnable(it) })
    }

    fun row(position: Int): DatedRow<T> = getItem(position)

    fun isHeader(position: Int) = getItem(position) is DatedRow.Header

    /** The position of the header of the day of the row at [position], or -1 */
    fun headerPositionFor(position: Int): Int = headerPositions.getOrElse(position) { -1 }

    override fun onCurrentListChanged(previousList: List<DatedRow<T>>, currentList: List<DatedRow<T>>) {
        headerPositions = headerPositions(currentList)
    }

    override fun getItemViewType(position: Int) = if (isHeader(position)) VIEW_TYPE_HEADER else VIEW_TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == VIEW_TYPE_HEADER) createHeaderViewHolder(parent) else onCreateItemViewHolder(parent)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
        onBindViewHolder(holder, position, emptyList())

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        when (val row = getItem(position)) {
            is DatedRow.Header -> (holder as HeaderViewHolder).bind(row.day)
            is DatedRow.Item -> onBindItem(holder, row.value, payloads)
        }
    }

    open fun createHeaderViewHolder(parent: ViewGroup): HeaderViewHolder {
        val vb = ViewDatedListHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HeaderViewHolder(vb.root, vb.tvDate, ::headerTitle)
    }

    /** The title of the header of the [day] (or of the [DatedRow.PINNED_GROUP]) */
    protected open fun headerTitle(day: Long): String = DateFormat.getDateInstance().format(Date(day))

    class HeaderViewHolder(view: View, private val title: TextView, private val titleOf: (Long) -> String) :
        RecyclerView.ViewHolder(view) {
        var day = Long.MIN_VALUE
            private set

        fun bind(day: Long) {
            this.day = day
            title.text = titleOf(day)
        }
    }

    private class RowDiff<T : Any>(private val items: DiffUtil.ItemCallback<T>) : DiffUtil.ItemCallback<DatedRow<T>>() {
        override fun areItemsTheSame(oldRow: DatedRow<T>, newRow: DatedRow<T>) = when {
            oldRow is DatedRow.Header && newRow is DatedRow.Header -> oldRow.day == newRow.day
            oldRow is DatedRow.Item && newRow is DatedRow.Item -> items.areItemsTheSame(oldRow.value, newRow.value)
            else -> false
        }

        override fun areContentsTheSame(oldRow: DatedRow<T>, newRow: DatedRow<T>) = when {
            oldRow is DatedRow.Item && newRow is DatedRow.Item -> items.areContentsTheSame(oldRow.value, newRow.value)
            else -> true
        }

        override fun getChangePayload(oldRow: DatedRow<T>, newRow: DatedRow<T>): Any? =
            if (oldRow is DatedRow.Item && newRow is DatedRow.Item) items.getChangePayload(oldRow.value, newRow.value) else null
    }

    companion object {
        const val VIEW_TYPE_ITEM = 0
        const val VIEW_TYPE_HEADER = 1
    }
}
