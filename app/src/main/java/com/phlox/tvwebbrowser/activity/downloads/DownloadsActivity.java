package com.phlox.tvwebbrowser.activity.downloads;

import android.app.ListActivity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.support.v4.content.FileProvider;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.phlox.asql.ASQL;
import com.phlox.tvwebbrowser.BuildConfig;
import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.model.Download;
import com.phlox.tvwebbrowser.service.downloads.DownloadService;
import com.phlox.tvwebbrowser.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.phlox.tvwebbrowser.R.string.url;

public class DownloadsActivity extends ListActivity implements AdapterView.OnItemClickListener, DownloadService.Listener, AdapterView.OnItemLongClickListener {
    private TextView tvPlaceholder;
    private DownloadListAdapter adapter;
    private ASQL asql;
    private boolean loading = false;
    private DownloadService downloadsService;
    private List<DownloadService.Listener> listeners = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);
        tvPlaceholder = (TextView) findViewById(R.id.tvPlaceholder);

        adapter = new DownloadListAdapter(this);
        setListAdapter(adapter);
        asql = ASQL.getDefault(this);

        getListView().setOnScrollListener(onListScrollListener);
        getListView().setOnItemClickListener(this);
        getListView().setOnItemLongClickListener(this);

        loadItems();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, DownloadService.class), downloadsServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        unbindService(downloadsServiceConnection);
        super.onStop();
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
        DownloadListItemView v = (DownloadListItemView) view;
        if (v.download.isDateHeader) {
            return;
        }
        File file = new File(v.download.filepath);
        if (!file.exists()) {
            Utils.showToast(this, R.string.file_not_found);
        }
        if (v.download.size != v.download.bytesReceived) {
            return;
        }
        //Uri pathUri = Uri.fromFile(file);
        Uri pathUri = FileProvider.getUriForFile(DownloadsActivity.this,
                BuildConfig.APPLICATION_ID + ".provider",
                file);
        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        String extension = getFileExtension(v.download.filepath);
        if (extension != null) {
            openIntent.setDataAndType(pathUri, MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension));
        } else {
            openIntent.setData(pathUri);
        }
        openIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(openIntent);
        } catch (ActivityNotFoundException e) {
            Utils.showToast(this, getString(R.string.no_app_for_file_type));
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> adapterView, View view, int i, long l) {
        final DownloadListItemView v = (DownloadListItemView) view;
        if (v.download.isDateHeader) {
            return true;
        }
        if (v.download.size == Download.BROKEN_MARK || v.download.size == Download.CANCELLED_MARK ||
                v.download.size == v.download.bytesReceived) {
            showFinishedDownloadOptionsPopup(v);
        } else {
            showUnfinishedDownloadOptionsPopup(v);
        }
        return true;
    }

    private void showUnfinishedDownloadOptionsPopup(final DownloadListItemView v) {
        PopupMenu pm = new PopupMenu(this, v, Gravity.BOTTOM);
        pm.getMenu().add(R.string.cancel);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                downloadsService.cancelDownload(v.download);
                return true;
            }
        });
        pm.show();
    }

    private void showFinishedDownloadOptionsPopup(final DownloadListItemView v) {
        PopupMenu pm = new PopupMenu(this, v, Gravity.BOTTOM);
        pm.getMenu().add(0, 1, 1, R.string.open_folder);
        pm.getMenu().add(0, 2, 2, R.string.delete);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                switch (menuItem.getItemId()) {
                    case 1:
                        openFolder(v);
                        break;
                    case 2:
                        deleteItem(v);
                        break;
                }
                return true;
            }
        });
        pm.show();
    }

    private void openFolder(DownloadListItemView v) {
        Uri uri = Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "resource/folder");
        if (intent.resolveActivityInfo(getPackageManager(), 0) != null) {
            startActivity(intent);
        } else {
            Utils.showToast(this, R.string.no_file_explorer_msg);
        }
    }

    private void deleteItem(final DownloadListItemView v) {
        new File(v.download.filepath).delete();
        asql.delete(v.download, new ASQL.ResultCallback<Integer>() {
            @Override
            public void onDone(Integer result, Exception exception) {
                if (exception != null) {
                    Utils.showToast(DownloadsActivity.this, R.string.error);
                } else {
                    adapter.remove(v.download);
                }
            }
        });
    }

    @Override
    public void onDownloadUpdated(Download downloadInfo) {
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onDownloadUpdated(downloadInfo);
        }
    }

    @Override
    public void onDownloadError(Download downloadInfo, int responseCode, String responseMessage) {
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onDownloadError(downloadInfo, responseCode, responseMessage);
        }
    }

    @Override
    public void onAllDownloadsComplete() {
    }

    public void registerListener(DownloadService.Listener listener) {
        listeners.add(listener);
    }

    public void unregisterListener(DownloadService.Listener listener) {
        listeners.remove(listener);
    }

    private void loadItems() {
        if (loading) {
            return;
        }
        loading = true;

        asql.queryAll(Download.class, "SELECT * FROM downloads ORDER BY time DESC LIMIT 100 OFFSET ?",
                sqlCallback, Long.toString(adapter.getRealCount()));
    }

    private ASQL.ResultCallback<List<Download>> sqlCallback = new ASQL.ResultCallback<List<Download>>() {
        @Override
        public void onDone(List<Download> result, Exception error) {
            loading = false;
            if (result != null) {
                if (!result.isEmpty()) {
                    tvPlaceholder.setVisibility(View.GONE);
                    adapter.addItems(result);
                    getListView().requestFocus();
                }
            } else {
                Utils.showToast(DownloadsActivity.this, R.string.error);
            }
        }
    };

    AbsListView.OnScrollListener onListScrollListener = new AbsListView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(AbsListView view, int scrollState) {

        }

        @Override
        public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            if (totalItemCount != 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 1) {
                loadItems();
            }
        }
    };

    ServiceConnection downloadsServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            DownloadService.Binder binder = (DownloadService.Binder) service;
            downloadsService = binder.getService();
            downloadsService.registerListener(DownloadsActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            downloadsService.unregisterListener(DownloadsActivity.this);
            downloadsService = null;
        }
    };

    static String getFileExtension(String filePath) {
        String result = "";
        String[] parts = filePath.split("\\.");
        if (parts.length > 0)
            result = parts[parts.length - 1];
        return result;
    }
}
