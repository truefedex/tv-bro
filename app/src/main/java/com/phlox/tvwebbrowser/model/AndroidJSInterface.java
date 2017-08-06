package com.phlox.tvwebbrowser.model;

import android.webkit.JavascriptInterface;

import com.phlox.tvwebbrowser.activity.main.MainActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Created by fedex on 11.12.16.
 */
public class AndroidJSInterface {
    private MainActivity activity;
    private String suggestions = "[]";

    @JavascriptInterface
    public void search(final String string) {
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.search(string);
            }
        });
    }

    @JavascriptInterface
    public void navigate(final String string) {
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.navigate(string);
            }
        });
    }

    @JavascriptInterface
    public String suggestions() {
        return suggestions;
    }

    public void setActivity(MainActivity activity) {
        this.activity = activity;
    }

    public void setSuggestions(List<HistoryItem> frequentlyUsedURLs) throws JSONException {
        JSONArray jsArr = new JSONArray();
        for (HistoryItem item : frequentlyUsedURLs) {
            JSONObject jsObj = new JSONObject();
            jsObj.put("url", item.url);
            jsObj.put("title", item.title);
            jsArr.put(jsObj);
        }
        suggestions = jsArr.toString();
    }
}
