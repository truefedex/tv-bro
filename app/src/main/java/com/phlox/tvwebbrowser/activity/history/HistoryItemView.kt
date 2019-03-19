package com.phlox.tvwebbrowser.activity.history

import android.content.Context
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView

import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.HistoryItem

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Created by fedex on 29.12.16.
 */

class HistoryItemView(context: Context, private val viewType: Int) : FrameLayout(context) {
    private var tvDate: TextView? = null
    private var tvTitle: TextView? = null
    private var tvURL: TextView? = null
    private var tvTime: TextView? = null
    private var cbSelection: CheckBox? = null
    var historyItem: HistoryItem? = null

    init {
        LayoutInflater.from(context).inflate(
                if (viewType == HistoryAdapter.VIEW_TYPE_HEADER)
                    R.layout.view_history_header_item
                else
                    R.layout.view_history_item, this)
        when (viewType) {
            HistoryAdapter.VIEW_TYPE_HEADER -> tvDate = findViewById(R.id.tvDate)
            HistoryAdapter.VIEW_TYPE_HISTORY_ITEM -> {
                tvTitle = findViewById(R.id.tvTitle)
                tvURL = findViewById(R.id.tvURL)
                tvTime = findViewById(R.id.tvTime)
                cbSelection = findViewById(R.id.cbSelection)
            }
        }
    }

    fun setHistoryItem(historyItem: HistoryItem, multiselectMode: Boolean) {
        this.historyItem = historyItem
        when (viewType) {
            HistoryAdapter.VIEW_TYPE_HEADER -> {
                val df = SimpleDateFormat.getDateInstance()
                tvDate!!.text = df.format(Date(historyItem.time))
            }
            HistoryAdapter.VIEW_TYPE_HISTORY_ITEM -> {
                tvTitle!!.text = historyItem.title
                tvURL!!.text = historyItem.url
                val sdf = SimpleDateFormat("HH:mm")
                tvTime!!.text = sdf.format(Date(historyItem.time))
                cbSelection!!.visibility = if (multiselectMode) VISIBLE else GONE
                cbSelection!!.isChecked = historyItem.selected
            }
        }
    }

    fun setSelection(selected: Boolean) {
        if (viewType == HistoryAdapter.VIEW_TYPE_HEADER) return
        cbSelection!!.isChecked = selected
        historyItem?.selected = selected
    }
}
