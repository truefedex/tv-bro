package com.phlox.tvwebbrowser.activity.downloads;

import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.phlox.tvwebbrowser.model.Download;
import com.phlox.tvwebbrowser.utils.Utils;

import java.util.ArrayList;
import java.util.List;

import de.halfbit.pinnedsection.PinnedSectionListView;

/**
 * Created by PDT on 24.01.2017.
 */

public class DownloadListAdapter extends BaseAdapter implements PinnedSectionListView.PinnedSectionListAdapter{
    public static final int VIEW_TYPE_DOWNLOAD_ITEM = 0;
    public static final int VIEW_TYPE_HEADER = 1;
    private final DownloadsActivity downloadsActivity;
    private List<Download> downloads = new ArrayList<>();
    private long lastHeaderDate = -1;
    private long realCount = 0;

    public DownloadListAdapter(DownloadsActivity downloadsActivity) {
        this.downloadsActivity = downloadsActivity;
    }

    public void addItems(List<Download> items) {
        if (items.isEmpty()) {
            return;
        }
        for (Download download: items) {
            if (!Utils.isSameDate(download.time, lastHeaderDate)) {
                lastHeaderDate = download.time;
                this.downloads.add(Download.createDateHeaderInfo(download.time));
            }
            this.downloads.add(download);
            realCount++;
        }
        notifyDataSetChanged();
    }

    public long getRealCount() {
        return realCount;
    }

    @Override
    public int getCount() {
        return downloads.size();
    }

    @Override
    public Object getItem(int i) {
        return downloads.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        DownloadListItemView hiv;
        if (convertView != null) {
            hiv = (DownloadListItemView) convertView;
        } else {
            hiv = new DownloadListItemView(downloadsActivity, getItemViewType(position));
        }
        hiv.setDownload(downloads.get(position));
        return hiv;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return downloads.get(position).isDateHeader ? VIEW_TYPE_HEADER : VIEW_TYPE_DOWNLOAD_ITEM;
    }

    @Override
    public boolean isItemViewTypePinned(int viewType) {
        return viewType == VIEW_TYPE_HEADER;
    }

    public void remove(Download download) {
        downloads.remove(download);
        notifyDataSetChanged();
    }
}
