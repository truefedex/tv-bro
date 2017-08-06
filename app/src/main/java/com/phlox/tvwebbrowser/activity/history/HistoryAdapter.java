package com.phlox.tvwebbrowser.activity.history;

import android.util.MutableBoolean;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.phlox.tvwebbrowser.model.HistoryItem;
import com.phlox.tvwebbrowser.utils.Utils;

import java.util.ArrayList;
import java.util.List;

import de.halfbit.pinnedsection.PinnedSectionListView;

/**
 * Created by fedex on 29.12.16.
 */

public class HistoryAdapter extends BaseAdapter implements PinnedSectionListView.PinnedSectionListAdapter {
    public static final int VIEW_TYPE_HISTORY_ITEM = 0;
    public static final int VIEW_TYPE_HEADER = 1;
    private List<HistoryItem> items = new ArrayList<>();
    private long lastHeaderDate = -1;
    private long realCount = 0;
    private boolean multiselectMode = false;
    private ArrayList<HistoryItem> _tmpSelected = new ArrayList<>();

    public void addItems(List<HistoryItem> items) {
        if (items.isEmpty()) {
            return;
        }
        for (HistoryItem hi: items) {
            if (!Utils.isSameDate(hi.time, lastHeaderDate)) {
                lastHeaderDate = hi.time;
                this.items.add(HistoryItem.createDateHeaderInfo(hi.time));
            }
            this.items.add(hi);
            realCount++;
        }
        notifyDataSetChanged();
    }

    public long getRealCount() {
        return realCount;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        HistoryItemView hiv;
        if (convertView != null) {
            hiv = (HistoryItemView) convertView;
        } else {
            hiv = new HistoryItemView(parent.getContext(), getItemViewType(position));
        }
        hiv.setHistoryItem(items.get(position), multiselectMode);
        return hiv;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).isDateHeader ? VIEW_TYPE_HEADER : VIEW_TYPE_HISTORY_ITEM;
    }

    @Override
    public boolean isItemViewTypePinned(int viewType) {
        return viewType == VIEW_TYPE_HEADER;
    }

    public void erase() {
        items.clear();
        notifyDataSetChanged();
    }

    public void remove(HistoryItem historyItem) {
        items.remove(historyItem);
        notifyDataSetChanged();
    }

    public void remove(List<HistoryItem> selectedItems) {
        items.removeAll(selectedItems);
        notifyDataSetChanged();
    }

    public void setMultiselectMode(boolean multiselectMode) {
        this.multiselectMode = multiselectMode;
        if (!multiselectMode) {
            for (HistoryItem hi: items) {
                hi.selected = false;
            }
        }
        notifyDataSetChanged();
    }

    public boolean isMultiselectMode() {
        return multiselectMode;
    }

    public List<HistoryItem> getSelectedItems() {
        _tmpSelected.clear();
        for (HistoryItem hi: items) {
            if (hi.selected) {
                _tmpSelected.add(hi);
            }
        }
        return _tmpSelected;
    }
}
