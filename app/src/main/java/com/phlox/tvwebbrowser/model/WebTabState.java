package com.phlox.tvwebbrowser.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Parcel;
import android.webkit.WebChromeClient;

import com.phlox.tvwebbrowser.activity.main.MainActivity;
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx;
import com.phlox.tvwebbrowser.utils.Utils;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/**
 * Created by PDT on 24.08.2016.
 *
 * Class to store state of tab with webView
 */
public class WebTabState {
    public static final String BLOBS_DIR = "tabs_blobs";
    public WebViewEx webView;
    public Bundle savedState;
    public String currentOriginalUrl;
    public String currentTitle;
    public boolean selected;
    public Bitmap thumbnail;
    public String thumbnailHash;
    public boolean webPageInteractionDetected = false;
    public WebChromeClient webChromeClient;

    public WebTabState() {
    }

    public WebTabState(Context context, JSONObject json) {
        try {
            currentOriginalUrl = json.getString("url");
            currentTitle = json.getString("title");
            selected = json.getBoolean("selected");
            if (json.has("thumbnail")) {
                thumbnailHash = json.getString("thumbnail");
                File thumbnailFile = new File(context.getFilesDir().getAbsolutePath() +
                        File.separator + WebTabState.BLOBS_DIR +
                        File.separator + thumbnailHash);
                if (thumbnailFile.exists()) {
                    thumbnail = BitmapFactory.decodeFile(thumbnailFile.getAbsolutePath());
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public JSONObject toJson(Context context, boolean storeFiles) {
        JSONObject store = new JSONObject();
        try {
            store.put("url", currentOriginalUrl);
            store.put("title", currentTitle);
            store.put("selected", selected);
            if (storeFiles) {
                File tabsBlobsDir = new File(context.getFilesDir().getAbsolutePath() + File.separator + WebTabState.BLOBS_DIR);
                if (tabsBlobsDir.exists() || tabsBlobsDir.mkdir()) {
                    if (thumbnail != null) {
                        if (thumbnailHash != null) {
                            removeThumbnailFile(context);
                            thumbnailHash = null;
                        }
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        thumbnail.compress(Bitmap.CompressFormat.PNG, 100, baos); //bm is the bitmap object
                        byte[] bitmapBytes = baos.toByteArray();
                        String hash = Utils.MD5_Hash(bitmapBytes);
                        if (hash != null) {
                            File file = new File(tabsBlobsDir.getAbsolutePath() + File.separator + hash);
                            try {
                                FileOutputStream fis = new FileOutputStream(file);
                                fis.write(bitmapBytes);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            thumbnailHash = hash;
                            store.put("thumbnail", thumbnailHash);
                        }
                    }
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return store;
    }

    public void removeFiles(Context context) {
        if (thumbnailHash != null) {
            removeThumbnailFile(context);
        }
    }

    private void removeThumbnailFile(Context context) {
        File thumbnailFile = new File(context.getFilesDir().getAbsolutePath() +
                File.separator + WebTabState.BLOBS_DIR +
                File.separator + thumbnailHash);
        thumbnailFile.delete();
    }

    public void restoreWebView() {
        if (savedState != null) {
            webView.restoreState(savedState);
        } else if (currentOriginalUrl != null) {
            webView.loadUrl(currentOriginalUrl);
        }
    }

    public void recycleWebView() {
        if (webView != null) {
            savedState = new Bundle();
            webView.saveState(savedState);
            webView = null;
        }
    }
}
