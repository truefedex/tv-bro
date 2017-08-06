package com.phlox.tvwebbrowser.activity.history;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.model.HistoryItem;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by fedex on 29.12.16.
 */

public class HistoryItemView extends FrameLayout {
    private final int viewType;
    private TextView tvDate;
    private TextView tvTitle;
    private TextView tvURL;
    private TextView tvTime;
    private CheckBox cbSelection;
    public HistoryItem historyItem;

    public HistoryItemView(Context context, int itemViewType) {
        super(context);
        this.viewType = itemViewType;
        LayoutInflater.from(context).inflate(
                (itemViewType == HistoryAdapter.VIEW_TYPE_HEADER ?
                        R.layout.view_history_header_item :
                        R.layout.view_history_item), this);
        switch (viewType) {
            case HistoryAdapter.VIEW_TYPE_HEADER:
                tvDate = findViewById(R.id.tvDate);
                break;
            case HistoryAdapter.VIEW_TYPE_HISTORY_ITEM:
                tvTitle = findViewById(R.id.tvTitle);
                tvURL = findViewById(R.id.tvURL);
                tvTime = findViewById(R.id.tvTime);
                cbSelection = findViewById(R.id.cbSelection);
                break;
        }
    }

    public void setHistoryItem(HistoryItem historyItem, boolean multiselectMode) {
        this.historyItem = historyItem;
        switch (viewType) {
            case HistoryAdapter.VIEW_TYPE_HEADER:
                DateFormat df = SimpleDateFormat.getDateInstance();
                tvDate.setText(df.format(new Date(historyItem.time)));
                break;
            case HistoryAdapter.VIEW_TYPE_HISTORY_ITEM:
                tvTitle.setText(historyItem.title);
                tvURL.setText(historyItem.url);
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
                tvTime.setText(sdf.format(new Date(historyItem.time)));
                cbSelection.setVisibility(multiselectMode ? VISIBLE : GONE);
                cbSelection.setChecked(historyItem.selected);
                break;
        }
    }

    public void setSelection(boolean selected) {
        if (viewType == HistoryAdapter.VIEW_TYPE_HEADER) return;
        cbSelection.setChecked(selected);
        historyItem.selected = selected;
    }
}
