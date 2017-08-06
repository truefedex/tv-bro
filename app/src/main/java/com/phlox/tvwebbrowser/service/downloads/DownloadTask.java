package com.phlox.tvwebbrowser.service.downloads;

import android.webkit.CookieManager;

import com.phlox.tvwebbrowser.model.Download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Created by PDT on 23.01.2017.
 */

public class DownloadTask implements Runnable {
    public Download downloadInfo;
    public Callback callback;

    public interface Callback {
        void onProgress(DownloadTask task);
        void onError(DownloadTask task, int responseCode, String responseMessage);
        void onDone(DownloadTask task);
    }

    public DownloadTask(Download downloadInfo, Callback callback) {
        this.downloadInfo = downloadInfo;
        this.callback = callback;
    }

    @Override
    public void run() {
        InputStream input = null;
        OutputStream output = null;
        HttpURLConnection connection = null;
        try {
            URL url = new URL(downloadInfo.url);
            connection = (HttpURLConnection) url.openConnection();
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                downloadInfo.size = Download.BROKEN_MARK;
                callback.onError(this, connection.getResponseCode(), connection.getResponseMessage());
                return;
            }

            int fileLength = connection.getContentLength();
            downloadInfo.size = fileLength;

            input = connection.getInputStream();
            output = new FileOutputStream(downloadInfo.filepath);

            byte data[] = new byte[4096];
            long total = 0;
            int count;
            while ((count = input.read(data)) != -1) {
                if (isCancelled()) {
                    downloadInfo.bytesReceived = 0;
                    downloadInfo.size = Download.CANCELLED_MARK;
                    callback.onDone(this);
                    return;
                }
                total += count;
                output.write(data, 0, count);
                downloadInfo.bytesReceived = total;
                callback.onProgress(this);
            }
        } catch (Exception e) {
            downloadInfo.size = Download.BROKEN_MARK;
            callback.onError(this, 0, e.toString());
            return;
        } finally {
            try {
                if (output != null)
                    output.close();
                if (input != null)
                    input.close();
            } catch (IOException ignored) {
            }

            if (connection != null)
                connection.disconnect();
            if (isCancelled()) {
                new File(downloadInfo.filepath).delete();
            }
        }
        downloadInfo.size = downloadInfo.bytesReceived;
        callback.onDone(this);
    }

    public boolean isCancelled() {
        return downloadInfo.cancelled;
    }

    public void setCancelled(boolean cancelled) {
        downloadInfo.cancelled = cancelled;
    }
}
