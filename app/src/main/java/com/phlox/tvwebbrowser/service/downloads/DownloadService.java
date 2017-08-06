package com.phlox.tvwebbrowser.service.downloads;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.phlox.asql.ASQL;
import com.phlox.tvwebbrowser.model.Download;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by PDT on 23.01.2017.
 */

public class DownloadService extends Service {
    private List<DownloadTask> activeDownloads = new ArrayList<>();
    private ASQL asql;
    private ExecutorService executor = Executors.newCachedThreadPool();
    private List<Listener> listeners = new ArrayList<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private final IBinder binder = new Binder();

    public interface Listener {
        void onDownloadUpdated(Download downloadInfo);
        void onDownloadError(Download downloadInfo, int responseCode, String responseMessage);
        void onAllDownloadsComplete();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        asql = ASQL.getDefault(getApplicationContext());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void startDownloading(String url, String fullDestFilePath, String fileName) {
        final Download download = new Download();
        download.url = url;
        download.filename = fileName;
        download.filepath = fullDestFilePath;
        download.time = new Date().getTime();
        asql.save(download, new ASQL.ResultCallback<Long>() {
            @Override
            public void onDone(Long result, Exception exception) {

            }
        });
        DownloadTask downloadTask = new DownloadTask(download, downloadTasksListener);
        activeDownloads.add(downloadTask);
        executor.execute(downloadTask);
        startService(new Intent(this, DownloadService.class));
    }

    public void cancelDownload(Download download) {
        for (int i = 0; i < activeDownloads.size(); i++) {
            DownloadTask task = activeDownloads.get(i);
            if (task.downloadInfo.id == download.id) {
                task.setCancelled(true);
                break;
            }
        }
    }

    public void registerListener(Listener listener) {
        listeners.add(listener);
    }

    public void unregisterListener(Listener listener) {
        listeners.remove(listener);
    }

    DownloadTask.Callback downloadTasksListener = new DownloadTask.Callback() {
        static final int MIN_NOTIFY_TIMEOUT = 100;
        private long lastNotifyTime = System.currentTimeMillis();
        @Override
        public void onProgress(DownloadTask task) {
            long now = System.currentTimeMillis();
            if (now - lastNotifyTime > MIN_NOTIFY_TIMEOUT) {
                lastNotifyTime = now;
                notifyListeners(task);
            }
        }

        @Override
        public void onError(DownloadTask task, int responseCode, String responseMessage) {
            notifyListenersAboutError(task, responseCode, responseMessage);
        }

        @Override
        public void onDone(DownloadTask task) {
            notifyListenersAboutDownloadDone(task);
        }
    };

    private void onTaskEnded(final DownloadTask task) {
        asql.save(task.downloadInfo, new ASQL.ResultCallback<Long>() {
            @Override
            public void onDone(Long result, Exception exception) {
                activeDownloads.remove(task);
                if (activeDownloads.isEmpty()) {
                    for (int i = 0; i < listeners.size(); i++) {
                        listeners.get(i).onAllDownloadsComplete();
                    }
                    stopSelf();
                }
            }
        });
    }

    private void notifyListenersAboutDownloadDone(final DownloadTask task) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < listeners.size(); i++) {
                    listeners.get(i).onDownloadUpdated(task.downloadInfo);
                }
                onTaskEnded(task);
            }
        });
    }

    private void notifyListenersAboutError(final DownloadTask task, final int responseCode, final String responseMessage) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < listeners.size(); i++) {
                    listeners.get(i).onDownloadError(task.downloadInfo, responseCode, responseMessage);
                }
                onTaskEnded(task);
            }
        });
    }

    private void notifyListeners(final DownloadTask task) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < listeners.size(); i++) {
                    listeners.get(i).onDownloadUpdated(task.downloadInfo);
                }
            }
        });
    }

    public class Binder extends android.os.Binder {
        public DownloadService getService() {
            return DownloadService.this;
        }
    }
}
