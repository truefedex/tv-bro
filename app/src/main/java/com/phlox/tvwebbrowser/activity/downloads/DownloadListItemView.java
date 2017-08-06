package com.phlox.tvwebbrowser.activity.downloads;

import android.content.Context;
import android.graphics.Color;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.model.Download;
import com.phlox.tvwebbrowser.service.downloads.DownloadService;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by PDT on 24.01.2017.
 */

public class DownloadListItemView extends FrameLayout implements DownloadService.Listener {
    private final int viewType;
    private final DownloadsActivity downloadsActivity;
    private int defaultTextColor;
    private TextView tvDate;
    private TextView tvTitle;
    private TextView tvURL;
    private TextView tvTime;
    private ProgressBar progressBar;
    private ProgressBar progressBar2;
    private TextView tvSize;
    public Download download;

    public DownloadListItemView(DownloadsActivity downloadsActivity, int itemViewType) {
        super(downloadsActivity);
        this.downloadsActivity = downloadsActivity;
        this.viewType = itemViewType;
        LayoutInflater.from(downloadsActivity).inflate(
                (itemViewType == DownloadListAdapter.VIEW_TYPE_HEADER ?
                        R.layout.view_history_header_item :
                        R.layout.view_download_item), this);
        switch (viewType) {
            case DownloadListAdapter.VIEW_TYPE_HEADER:
                tvDate = (TextView) findViewById(R.id.tvDate);
                break;
            case DownloadListAdapter.VIEW_TYPE_DOWNLOAD_ITEM:
                tvTitle = (TextView) findViewById(R.id.tvTitle);
                tvURL = (TextView) findViewById(R.id.tvURL);
                tvTime = (TextView) findViewById(R.id.tvTime);
                tvSize = (TextView) findViewById(R.id.tvSize);
                defaultTextColor = tvSize.getCurrentTextColor();
                progressBar = (ProgressBar) findViewById(R.id.progressBar);
                progressBar2 = (ProgressBar) findViewById(R.id.progressBar2);
                break;
        }
    }

    public void setDownload(Download download) {
        this.download = download;
        switch (viewType) {
            case DownloadListAdapter.VIEW_TYPE_HEADER:
                DateFormat df = SimpleDateFormat.getDateInstance();
                tvDate.setText(df.format(new Date(download.time)));
                break;
            case DownloadListAdapter.VIEW_TYPE_DOWNLOAD_ITEM:
                tvTitle.setText(download.filename);
                tvURL.setText(download.url);
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
                tvTime.setText(sdf.format(new Date(download.time)));
                updateUI(download);
                break;
        }
    }

    private void updateUI(Download download) {
        this.download.size = download.size;
        this.download.bytesReceived = download.bytesReceived;
        progressBar.setVisibility(INVISIBLE);
        progressBar2.setVisibility(GONE);
        tvSize.setTextColor(defaultTextColor);
        if (download.size == Download.CANCELLED_MARK) {
            tvSize.setText(R.string.cancelled);
        } else if (download.size == Download.BROKEN_MARK) {
            tvSize.setText(R.string.error);
            tvSize.setTextColor(Color.RED);
        } else if (download.size == 0) {
            tvSize.setText(Formatter.formatShortFileSize(getContext(), download.bytesReceived));
            progressBar2.setVisibility(VISIBLE);
        } else if (download.size > 0) {
            if (download.size == download.bytesReceived) {
                tvSize.setText(Formatter.formatShortFileSize(getContext(), download.size));
            } else {
                tvSize.setText(Formatter.formatShortFileSize(getContext(), download.bytesReceived) + "/\n" +
                        Formatter.formatShortFileSize(getContext(), download.size));
                progressBar.setVisibility(VISIBLE);
                progressBar.setProgress((int) (download.bytesReceived * 100 / download.size));
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        downloadsActivity.registerListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        downloadsActivity.unregisterListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onDownloadUpdated(Download downloadInfo) {
        if (downloadInfo.id == this.download.id) {
            updateUI(downloadInfo);
        }
    }

    @Override
    public void onDownloadError(Download downloadInfo, int responseCode, String responseMessage) {
        if (downloadInfo.id == this.download.id) {
            updateUI(downloadInfo);
        }
    }

    @Override
    public void onAllDownloadsComplete() {
    }
}
