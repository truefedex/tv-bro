
package com.phlox.tvwebbrowser.activity.main;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.SQLException;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;

import com.phlox.asql.ASQL;
import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity;
import com.phlox.tvwebbrowser.activity.history.HistoryActivity;
import com.phlox.tvwebbrowser.activity.main.adapter.TabsListAdapter;
import com.phlox.tvwebbrowser.activity.main.dialogs.FavoritesDialog;
import com.phlox.tvwebbrowser.activity.main.dialogs.SearchEngineConfigDialogFactory;
import com.phlox.tvwebbrowser.activity.main.dialogs.ShortcutDialog;
import com.phlox.tvwebbrowser.activity.main.dialogs.UserAgentConfigDialogFactory;
import com.phlox.tvwebbrowser.activity.main.view.CursorLayout;
import com.phlox.tvwebbrowser.activity.main.view.WebTabItemView;
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx;
import com.phlox.tvwebbrowser.activity.singleton.shortcuts.ShortcutMgr;
import com.phlox.tvwebbrowser.model.AndroidJSInterface;
import com.phlox.tvwebbrowser.model.Download;
import com.phlox.tvwebbrowser.model.FavoriteItem;
import com.phlox.tvwebbrowser.model.HistoryItem;
import com.phlox.tvwebbrowser.model.WebTabState;
import com.phlox.tvwebbrowser.service.downloads.DownloadService;
import com.phlox.tvwebbrowser.utils.AndroidBug5497Workaround;
import com.phlox.tvwebbrowser.utils.BaseAnimationListener;
import com.phlox.tvwebbrowser.utils.StringUtils;
import com.phlox.tvwebbrowser.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;


public class MainActivity extends Activity {
    private static final String TAG = MainActivity.class.getSimpleName();
    public static final String HOME_URL = "file:///android_asset/pages/new-tab.html";
    private static final int VOICE_SEARCH_REQUEST_CODE = 10001;
    private static final int MY_PERMISSIONS_REQUEST_WEB_PAGE_PERMISSIONS = 10002;
    private static final int MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS = 10003;
    private static final int MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 10004;
    private static final int PICKFILE_REQUEST_CODE = 10005;
    private static final int REQUEST_CODE_HISTORY_ACTIVITY = 10006;
    public static final String STATE_JSON = "state.json";
    public static final String SEARCH_ENGINE_URL_PREF_KEY = "search_engine_url";
    public static final String USER_AGENT_PREF_KEY = "user_agent";
    public static final String MAIN_PREFS_NAME = "main.xml";
    private Handler handler;
    private WebTabState currentTab;
    private List<WebTabState> tabsStates = new ArrayList<>();
    private TabsListAdapter tabsAdapter;
    private Size thumbnailesSize = null;
    private WebChromeClient.CustomViewCallback fullscreenViewCallback;
    private AlertDialog permRequestDialog;
    private PermissionRequest webPermissionsRequest;
    private ArrayList<String> reuestedResourcesForAlreadyGrantedPermissions;
    private String geoPermissionOrigin;
    private GeolocationPermissions.Callback geoPermissionsCallback;
    private boolean running;
    private String urlToDownload = null;
    private String originalDownloadFileName;
    private String userAgentForDownload;
    private ValueCallback<Uri[]> pickFileCallback;
    private AndroidJSInterface jsInterface = new AndroidJSInterface();
    private ASQL asql;
    private HistoryItem lastHistoryItem;
    private String searchEngineURL;
    private DownloadService downloadsService;
    private Animation downloadAnimation;

    private LinearLayout llMenu;
    private EditText etUrl;
    private FrameLayout flFullscreenContainer;
    private View fullScreenView;
    private ProgressBar progressBar;
    private LinearLayout llActionBar;
    private ImageButton ibMenu;
    private LinearLayout llMenuOverlay;
    private FrameLayout flMenuRightContainer;
    private ImageButton ibBack;
    private ImageButton ibForward;
    private ImageButton ibRefresh;
    private ImageButton ibVoiceSearch;
    private CursorLayout flWebViewContainer;
    private Button btnNewTab;
    private ListView lvTabs;
    private ProgressBar progressBarGeneric;
    private ImageButton ibDownloads;
    private ImageButton ibMore;
    private PopupMenu popupMenuMoreActions;
    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler();
        jsInterface.setActivity(this);
        setContentView(R.layout.activity_main);
        AndroidBug5497Workaround.assistActivity(this);
        llMenu = findViewById(R.id.llMenu);
        llMenuOverlay = findViewById(R.id.llMenuOverlay);
        llActionBar = findViewById(R.id.llActionBar);
        etUrl = findViewById(R.id.etUrl);
        flWebViewContainer = findViewById(R.id.flWebViewContainer);
        flFullscreenContainer = findViewById(R.id.fullscreen_container);
        progressBar = findViewById(R.id.progressBar);
        ibMenu = findViewById(R.id.ibMenu);
        flMenuRightContainer = findViewById(R.id.flMenuRightContainer);
        ibBack = findViewById(R.id.ibBack);
        ibForward = findViewById(R.id.ibForward);
        ibRefresh = findViewById(R.id.ibRefresh);
        ibVoiceSearch = findViewById(R.id.ibVoiceSearch);
        ibDownloads = findViewById(R.id.ibDownloads);
        ibMore = findViewById(R.id.ibMore);
        btnNewTab = findViewById(R.id.btnNewTab);
        lvTabs = findViewById(R.id.lvTabs);
        progressBarGeneric = findViewById(R.id.progressBarGeneric);

        llMenuOverlay.setVisibility(View.GONE);
        llActionBar.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);

        tabsAdapter = new TabsListAdapter(tabsStates, tabsEventsListener);
        lvTabs.setAdapter(tabsAdapter);
        lvTabs.setItemsCanFocus(true);

        flWebViewContainer.setCallback(new CursorLayout.Callback() {
            @Override
            public void onUserInteraction() {
                if (currentTab != null) {
                    if (!currentTab.webPageInteractionDetected) {
                        currentTab.webPageInteractionDetected = true;
                        logVisitedHistory(currentTab.currentTitle, currentTab.currentOriginalUrl);
                    }
                }
            }
        });

        btnNewTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openInNewTab(HOME_URL);
            }
        });

        PackageManager pm = getPackageManager();
        List<ResolveInfo> activities = pm.queryIntentActivities(
                new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0);
        if (activities.size() == 0) {
            ibVoiceSearch.setVisibility(View.GONE);
        }

        ibVoiceSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                initiateVoiceSearch();
            }
        });

        /*ibHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                navigate(HOME_URL);
            }
        });*/
        ibBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                navigateBack();
            }
        });
        ibForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (currentTab != null && currentTab.webView.canGoForward()) {
                    currentTab.webView.goForward();
                }
            }
        });
        ibRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refresh();
            }
        });

        ibMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

        ibDownloads.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, DownloadsActivity.class));
            }
        });

        ibMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (popupMenuMoreActions == null) {
                    popupMenuMoreActions = new PopupMenu(MainActivity.this, ibMore, Gravity.BOTTOM);
                    popupMenuMoreActions.inflate(R.menu.action_more);
                    popupMenuMoreActions.setOnMenuItemClickListener(onMenuMoreItemClickListener);
                }
                popupMenuMoreActions.show();
            }
        });

        flMenuRightContainer.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                if (focused && llMenuOverlay.getVisibility() == View.VISIBLE) {
                    hideMenuOverlay();
                }
            }
        });

        etUrl.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                if (focused) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(etUrl, InputMethodManager.SHOW_FORCED);
                    handler.postDelayed(new Runnable() {//workaround an android TV bug
                        @Override
                        public void run() {
                            etUrl.selectAll();
                        }
                    }, 500);
                }
            }
        });

        etUrl.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int i, KeyEvent keyEvent) {
                switch (keyEvent.getKeyCode()) {
                    case KeyEvent.KEYCODE_ENTER:
                        if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                            imm.hideSoftInputFromWindow(etUrl.getWindowToken(), 0);
                            hideMenuOverlay();
                            search(etUrl.getText().toString());
                            currentTab.webView.requestFocus();
                        }
                        return true;
                }
                return false;
            }
        });

        asql = ASQL.getDefault(getApplicationContext());

        loadState();
    }

    public void navigateBack() {
        if (currentTab != null && currentTab.webView.canGoBack()) {
            currentTab.webView.goBack();
        }
    }

    public void refresh() {
        if (currentTab != null) {
            currentTab.webView.reload();
        }
    }

    @Override
    protected void onDestroy() {
        jsInterface.setActivity(null);
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        saveState();
        super.onStop();
    }

    private void saveState() {
        new Thread() {
            @Override
            public void run() {
                JSONObject store = new JSONObject();
                JSONArray tabsStore = new JSONArray();
                for (WebTabState tab : tabsStates) {
                    JSONObject tabJson = tab.toJson(MainActivity.this, true);
                    tabsStore.put(tabJson);
                }
                try {
                    store.put("tabs", tabsStore);
                    FileOutputStream fos = openFileOutput(STATE_JSON, MODE_PRIVATE);
                    try {
                        fos.write(store.toString().getBytes());
                    } finally {
                        fos.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri intentUri = intent.getData();
        if (intentUri != null) {
            openInNewTab(intentUri.toString());
        }
    }

    private void loadState() {
        progressBarGeneric.setVisibility(View.VISIBLE);
        progressBarGeneric.requestFocus();
        new Thread() {
            @Override
            public void run() {
                initHistory();

                List<WebTabState> tabsStates = getWebTabStates();
                OnTabsLoadedRunnable onTabsLoadedRunnable = new OnTabsLoadedRunnable(tabsStates);
                runOnUiThread(onTabsLoadedRunnable);
            }
        }.start();

        prefs = getSharedPreferences(MAIN_PREFS_NAME, MODE_PRIVATE);
        searchEngineURL = prefs.getString(SEARCH_ENGINE_URL_PREF_KEY, "");
        if ("".equals(searchEngineURL)) {
            SearchEngineConfigDialogFactory.show(this, searchEngineURL, prefs, false, new SearchEngineConfigDialogFactory.Callback() {
                @Override
                public void onDone(String url) {
                    searchEngineURL = url;
                }
            });
        }
    }

    private void initHistory() {
        long count = asql.count(HistoryItem.class);
        if (count > 5000) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.MONTH, -3);
            asql.getDB().delete("history" ,"time < ?", new String[]{Long.toString(c.getTime().getTime())});
        }
        try {
            List<HistoryItem> result = asql.queryAll(HistoryItem.class, "SELECT * FROM history ORDER BY time DESC LIMIT 1", null);
            if (!result.isEmpty()) {
                lastHistoryItem = result.get(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            List<HistoryItem> frequentlyUsedUrls = asql.queryAll(HistoryItem.class,
                    "SELECT title, url, count(url) as cnt , max(time) as time FROM history GROUP BY title, url ORDER BY cnt DESC, time DESC LIMIT 6", null);
            jsInterface.setSuggestions(frequentlyUsedUrls);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<WebTabState> getWebTabStates() {
        final List<WebTabState> tabsStates = new ArrayList<>();
        try {
            FileInputStream fis = openFileInput(STATE_JSON);
            String storeStr = StringUtils.streamToString(fis);
            JSONObject store = new JSONObject(storeStr);
            JSONArray tabsStore = store.getJSONArray("tabs");
            for (int i = 0; i < tabsStore.length(); i++) {
                WebTabState tab = new WebTabState(MainActivity.this, tabsStore.getJSONObject(i));
                tabsStates.add(tab);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return tabsStates;
    }

    private void openInNewTab(String url) {
        if (url == null) {
            return;
        }
        WebTabState tab = new WebTabState();
        tab.currentOriginalUrl = url;
        createWebView(tab);
        tabsStates.add(0, tab);
        changeTab(tab);
        navigate(url);
    }

    private void closeTab(WebTabState tab) {
        int position = tabsStates.indexOf(tab);
        if (tabsStates.size() == 1) {
            tab.selected = false;
            tab.webView.onPause();
            flWebViewContainer.removeView(tab.webView);
            currentTab = null;
        } else if (position == tabsStates.size() - 1) {
            changeTab(tabsStates.get(position - 1));
        } else {
            changeTab(tabsStates.get(position + 1));
        }
        tabsStates.remove(tab);
        tabsAdapter.notifyDataSetChanged();

        tab.removeFiles(this);
    }

    private void changeTab(WebTabState newTab) {
        if (currentTab != null) {
            currentTab.selected = false;
            currentTab.webView.onPause();
            flWebViewContainer.removeView(currentTab.webView);
        }

        newTab.selected = true;
        currentTab = newTab;
        tabsAdapter.notifyDataSetChanged();
        if (currentTab.webView == null) {
            createWebView(currentTab);
            currentTab.restoreWebView();
            flWebViewContainer.addView(currentTab.webView);
        } else {
            flWebViewContainer.addView(currentTab.webView);
            currentTab.webView.onResume();
        }
        currentTab.webView.setNetworkAvailable(Utils.isNetworkConnected(this));

        etUrl.setText(newTab.currentOriginalUrl);
        ibBack.setEnabled(newTab.webView.canGoBack());
        ibForward.setEnabled(newTab.webView.canGoForward());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView(final WebTabState tab) {
        tab.webView = new WebViewEx(this);
        tab.webView.addJavascriptInterface(jsInterface, "TVBro");

        tab.webView.setListener(new WebViewEx.Listener() {
            @Override
            public void onThumbnailReady(Bitmap thumbnail) {
                tab.thumbnail = thumbnail;
                tabsAdapter.notifyDataSetChanged();
            }

            @Override
            public void onOpenInNewTabRequested(String s) {
                openInNewTab(s);
            }

            @Override
            public void onDownloadRequested(String url) {
                String fileName = Uri.parse(url).getLastPathSegment();
                MainActivity.this.onDownloadRequested(url, fileName != null ? fileName : "url.html", tab.webView.getUaString());
            }
        });

        tab.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return super.onJsAlert(view, url, message, result);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                tab.webView.setVisibility(View.GONE);
                flFullscreenContainer.setVisibility(View.VISIBLE);
                flFullscreenContainer.addView(view);

                fullScreenView = view;
                fullscreenViewCallback = callback;
            }

            @Override
            public void onHideCustomView() {
                flFullscreenContainer.removeView(fullScreenView);
                fullscreenViewCallback.onCustomViewHidden();
                fullScreenView = null;

                tab.webView.setVisibility(View.VISIBLE);
                flFullscreenContainer.setVisibility(View.GONE);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(View.VISIBLE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    progressBar.setProgress(newProgress, true);
                } else {
                    progressBar.setProgress(newProgress);
                }
                handler.removeCallbacks(progressBarHideRunnable);
                if (newProgress == 100) {
                    handler.postDelayed(progressBarHideRunnable, 1000);
                } else {
                    handler.postDelayed(progressBarHideRunnable, 5000);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                tab.currentTitle = title;
                tabsAdapter.notifyDataSetChanged();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                webPermissionsRequest = request;
                permRequestDialog = new AlertDialog.Builder(MainActivity.this)
                        .setMessage(getString(R.string.web_perm_request_confirmation, TextUtils.join("\n", request.getResources())))
                        .setCancelable(false)
                        .setNegativeButton(R.string.deny, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                webPermissionsRequest.deny();
                                permRequestDialog = null;
                                webPermissionsRequest = null;
                            }
                        })
                        .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    List<String> neededPermissions = new ArrayList<>();
                                    reuestedResourcesForAlreadyGrantedPermissions = new ArrayList<>();
                                    for (String resource : webPermissionsRequest.getResources()) {
                                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                                neededPermissions.add(Manifest.permission.RECORD_AUDIO);
                                            } else {
                                                reuestedResourcesForAlreadyGrantedPermissions.add(resource);
                                            }
                                        } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                                            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                                neededPermissions.add(Manifest.permission.CAMERA);
                                            } else {
                                                reuestedResourcesForAlreadyGrantedPermissions.add(resource);
                                            }
                                        }
                                    }

                                    if (!neededPermissions.isEmpty()) {
                                        String[] permissionsArr = new String[neededPermissions.size()];
                                        neededPermissions.toArray(permissionsArr);
                                        requestPermissions(permissionsArr,
                                                MY_PERMISSIONS_REQUEST_WEB_PAGE_PERMISSIONS);
                                    } else {
                                        if (reuestedResourcesForAlreadyGrantedPermissions.isEmpty()) {
                                            webPermissionsRequest.deny();
                                            webPermissionsRequest = null;
                                        } else {
                                            String[] grantedResourcesArr = new String[reuestedResourcesForAlreadyGrantedPermissions.size()];
                                            reuestedResourcesForAlreadyGrantedPermissions.toArray(grantedResourcesArr);
                                            webPermissionsRequest.grant(grantedResourcesArr);
                                            webPermissionsRequest = null;
                                        }
                                    }
                                } else {
                                    webPermissionsRequest.grant(webPermissionsRequest.getResources());
                                    webPermissionsRequest = null;
                                }
                                permRequestDialog = null;
                            }
                        })
                        .create();
                permRequestDialog.show();
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (permRequestDialog != null) {
                    permRequestDialog.dismiss();
                    permRequestDialog = null;
                }
                webPermissionsRequest = null;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                geoPermissionOrigin = origin;
                geoPermissionsCallback = callback;
                permRequestDialog = new AlertDialog.Builder(MainActivity.this)
                        .setMessage(getString(R.string.web_perm_request_confirmation, getString(R.string.location)))
                        .setCancelable(false)
                        .setNegativeButton(R.string.deny, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                geoPermissionsCallback.invoke(geoPermissionOrigin, false, false);
                                permRequestDialog = null;
                                geoPermissionsCallback = null;
                            }
                        })
                        .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                            MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS);
                                } else {
                                    geoPermissionsCallback.invoke(geoPermissionOrigin, true, true);
                                    geoPermissionsCallback = null;
                                }
                                permRequestDialog = null;
                            }
                        })
                        .create();
                permRequestDialog.show();
            }

            @Override
            public void onGeolocationPermissionsHidePrompt() {
                if (permRequestDialog != null) {
                    permRequestDialog.dismiss();
                    permRequestDialog = null;
                }
                geoPermissionsCallback = null;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.i("TV Bro (" + consoleMessage.sourceId() + "[" + consoleMessage.lineNumber() + "])", consoleMessage.message());
                return true;
            }


            @SuppressWarnings("unused")
            public boolean onShowFileChooser(WebView mWebView, ValueCallback<Uri[]> callback, FileChooserParams fileChooserParams) {
                pickFileCallback = callback;
                try {
                    startActivityForResult(fileChooserParams.createIntent(), PICKFILE_REQUEST_CODE);
                } catch (ActivityNotFoundException e) {
                    pickFileCallback = null;
                    Utils.showToast(getApplicationContext(), getString(R.string.err_cant_open_file_chooser));
                    return false;
                }
                return true;
            }
        });

        tab.webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                ibBack.setEnabled(tab.webView.canGoBack());
                ibForward.setEnabled(tab.webView.canGoForward());
                if (tab.webView.getUrl() != null) {
                    tab.currentOriginalUrl = tab.webView.getUrl();
                } else if (url != null) {
                    tab.currentOriginalUrl = url;
                }
                etUrl.setText(tab.currentOriginalUrl);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (tab.webView == null || currentTab == null || view == null) {
                    return;
                }
                ibBack.setEnabled(tab.webView.canGoBack());
                ibForward.setEnabled(tab.webView.canGoForward());
                tab.webView.setNeedThumbnail(thumbnailesSize);
                tab.webView.postInvalidate();
                if (tab.webView.getUrl() != null) {
                    tab.currentOriginalUrl = tab.webView.getUrl();
                } else if (url != null) {
                    tab.currentOriginalUrl = url;
                }
                etUrl.setText(tab.currentOriginalUrl);

                String INITIAL_SCRIPT = "window.addEventListener(\"touchstart\", function(e) {\n" +
                        "        window.TVBRO_activeElement = e.target;\n" +
                        "});";
                tab.webView.evaluateJavascript(INITIAL_SCRIPT, null);
                currentTab.webPageInteractionDetected = false;
                if (HOME_URL.equals(url)) {
                    view.loadUrl("javascript:renderSuggestions()");
                }
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                super.onLoadResource(view, url);
            }
        });

        tab.webView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                if (focused && llMenuOverlay.getVisibility() == View.VISIBLE) {
                    hideMenuOverlay();
                }
            }
        });

        tab.webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimetype,
                                        long contentLength) {
                onDownloadRequested(url, URLUtil.guessFileName(url, contentDisposition, mimetype), userAgent != null ? userAgent : tab.webView.getUaString());
            }
        });
    }

    private void onDownloadRequested(String url, String originalDownloadFileName, String userAgent) {
        this.urlToDownload = url;
        this.originalDownloadFileName = originalDownloadFileName;
        this.userAgentForDownload = userAgent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE);
        } else {
            startDownload(url, originalDownloadFileName, userAgent);
        }
    }

    private void logVisitedHistory(String title, String url) {
        if (url != null &&
                (
                        (
                                lastHistoryItem != null && url.equals(lastHistoryItem.url)
                        ) || url.equals(HOME_URL)
                )
            ) {
            return;
        }

        lastHistoryItem = new HistoryItem();
        lastHistoryItem.url = url;
        lastHistoryItem.title = title == null ? "" : title;
        lastHistoryItem.time = new Date().getTime();
        asql.execInsert("INSERT INTO history (time, title, url) VALUES (:time, :title, :url)", lastHistoryItem, new ASQL.InsertResultCallback() {
            @Override
            public void onDone(long lastInsertRowId, SQLException exception) {
                if (exception != null) {
                    Log.e(TAG, exception.toString());
                } else {
                    //good!
                }
            }
        });
    }

    private void startDownload(String url, String originalFileName, String userAgent) {
        int extPos = originalFileName.lastIndexOf(".");
        boolean hasExt = extPos != -1;
        String ext = null;
        String prefix = null;
        if (hasExt) {
            ext = originalFileName.substring(extPos + 1);
            prefix = originalFileName.substring(0, extPos);
        }
        String fileName = originalFileName;
        int i = 0;
        while (new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + File.separator + fileName).exists()) {
            i++;
            if (hasExt) {
                fileName = prefix + "_(" + i + ")." + ext;
            } else {
                fileName = originalFileName + "_(" + i + ")";
            }
        }

        String fullDestFilePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + File.separator + fileName;
        downloadsService.startDownloading(url, fullDestFilePath, fileName, userAgent);

        Utils.showToast(this, getString(R.string.download_started,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + File.separator + fileName));
        showMenuOverlay();
        if (downloadAnimation == null) {
            downloadAnimation = AnimationUtils.loadAnimation(this, R.anim.infinite_fadeinout_anim);
            ibDownloads.startAnimation(downloadAnimation);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        for (WebTabState tab : tabsStates) {
            if (!tab.selected) {
                tab.recycleWebView();
            }
        }
        super.onTrimMemory(level);
    }

    public void initiateVoiceSearch() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speak));
        startActivityForResult(intent, VOICE_SEARCH_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_WEB_PAGE_PERMISSIONS: {
                if (webPermissionsRequest == null) {
                    return;
                }
                // If request is cancelled, the result arrays are empty.
                List<String> resources = new ArrayList<>();
                for (int i = 0; i < permissions.length; i++) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        if (Manifest.permission.CAMERA.equals(permissions[i])) {
                            resources.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
                        } else if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])) {
                            resources.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
                        }
                    }
                }
                resources.addAll(reuestedResourcesForAlreadyGrantedPermissions);
                if (resources.isEmpty()) {
                    webPermissionsRequest.deny();
                } else {
                    String[] resourcesArr = new String[resources.size()];
                    resources.toArray(resourcesArr);
                    webPermissionsRequest.grant(resourcesArr);
                }
                webPermissionsRequest = null;
                return;
            }
            case MY_PERMISSIONS_REQUEST_WEB_PAGE_GEO_PERMISSIONS: {
                if (geoPermissionsCallback == null) {
                    return;
                }
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    geoPermissionsCallback.invoke(geoPermissionOrigin, true, true);
                } else {
                    geoPermissionsCallback.invoke(geoPermissionOrigin, false, false);
                }
                geoPermissionsCallback = null;
                return;
            }
            case MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED && urlToDownload != null) {
                    startDownload(urlToDownload, originalDownloadFileName, userAgentForDownload);
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case VOICE_SEARCH_REQUEST_CODE: {
                if (resultCode == RESULT_OK) {
                    // Populate the wordsList with the String values the recognition engine thought it heard
                    ArrayList<String> matches = data.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS);
                    if (matches == null || matches.isEmpty()) {
                        Utils.showToast(this, getString(R.string.can_not_recognize));
                        return;
                    }
                    search(matches.get(0));
                    hideMenuOverlay();
                }
                break;
            }
            case PICKFILE_REQUEST_CODE: {
                if (resultCode == RESULT_OK && pickFileCallback != null) {
                    Uri[] uris = new Uri[]{data.getData()};
                    pickFileCallback.onReceiveValue(uris);
                }
                break;
            }
            case REQUEST_CODE_HISTORY_ACTIVITY:
                if (resultCode == RESULT_OK) {
                    String url = data.getStringExtra(HistoryActivity.KEY_URL);
                    navigate(url);
                    hideMenuOverlay();
                }
                break;

            default:
                super.onActivityResult(requestCode, resultCode, data);
        }

    }

    @Override
    protected void onResume() {
        running = true;
        super.onResume();
        IntentFilter intentFilter = new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE");
        registerReceiver(mConnectivityChangeReceiver, intentFilter);
        if (currentTab != null) {
            currentTab.webView.onResume();
        }
        bindService(new Intent(this, DownloadService.class), downloadsServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onPause() {
        unbindService(downloadsServiceConnection);
        if (currentTab != null) {
            currentTab.webView.onPause();
        }
        if (mConnectivityChangeReceiver != null) unregisterReceiver(mConnectivityChangeReceiver);
        super.onPause();
        running = false;
    }

    public void navigate(String url) {
        if (currentTab != null) {
            currentTab.webView.loadUrl(url);
        } else {
            openInNewTab(url);
        }
    }

    public void search(String text) {
        String trimmedLowercased = text.trim().toLowerCase();
        if (Patterns.WEB_URL.matcher(text).matches() || trimmedLowercased.startsWith("http://") || trimmedLowercased.startsWith("https://")) {
            if (!text.toLowerCase().contains("://")) {
                text = "http://" + text;
            }
            navigate(text);
        } else {
            String query = null;
            try {
                query = URLEncoder.encode(text, "utf-8");
            } catch (UnsupportedEncodingException e1) {
                e1.printStackTrace();
                Utils.showToast(this, R.string.error);
                return;
            }
            String searchUrl = searchEngineURL.replace("[query]", query);
            navigate(searchUrl);
        }
    }

    public void toggleMenu() {
        if (llMenuOverlay.getVisibility() == View.GONE) {
            showMenuOverlay();
        } else {
            hideMenuOverlay();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        ShortcutMgr shortcutMgr = ShortcutMgr.getInstance(this);
        int keyCode = event.getKeyCode() != 0 ? event.getKeyCode() : event.getScanCode();
        if (shortcutMgr.canProcessKeyCode(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                //nop
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                shortcutMgr.process(keyCode, this);
            }
             return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showMenuOverlay() {
        llMenuOverlay.setVisibility(View.VISIBLE);
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.menu_in_anim);
        anim.setAnimationListener(new BaseAnimationListener(){
            @Override
            public void onAnimationEnd(Animation animation) {
                ibMenu.requestFocus();
            }
        });
        llMenu.startAnimation(anim);

        llActionBar.setVisibility(View.VISIBLE);
        llActionBar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.actionbar_in_anim));
    }

    private void hideMenuOverlay() {
        if (llMenuOverlay.getVisibility() == View.GONE) {
            return;
        }
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.menu_out_anim);
        anim.setAnimationListener(new BaseAnimationListener() {
            @Override
            public void onAnimationEnd(Animation animation) {
                llMenuOverlay.setVisibility(View.GONE);
                if (currentTab != null) {
                    currentTab.webView.requestFocus();
                }
            }
        });
        llMenu.startAnimation(anim);

        anim = AnimationUtils.loadAnimation(this, android.R.anim.fade_out);
        anim.setAnimationListener(new BaseAnimationListener() {
            @Override
            public void onAnimationEnd(Animation animation) {
                llActionBar.setVisibility(View.GONE);
            }
        });
        llActionBar.startAnimation(anim);
    }

    Runnable progressBarHideRunnable = new Runnable() {
        @Override
        public void run() {
            Animation anim = AnimationUtils.loadAnimation(MainActivity.this, android.R.anim.fade_out);
            anim.setAnimationListener(new BaseAnimationListener() {
                @Override
                public void onAnimationEnd(Animation animation) {
                    progressBar.setVisibility(View.GONE);
                }
            });
            progressBar.startAnimation(anim);
        }
    };

    BroadcastReceiver mConnectivityChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
            if (currentTab != null) {
                currentTab.webView.setNetworkAvailable(isConnected);
            }
        }
    };

    WebTabItemView.Listener tabsEventsListener = new WebTabItemView.Listener() {
        @Override
        public void onTabSelected(WebTabState tab) {
            if (!tab.selected) {
                changeTab(tab);
            }
        }

        @Override
        public void onTabDeleteClicked(WebTabState tab) {
            closeTab(tab);
        }

        @Override
        public void onNeededThumbnailSizeCalculated(int width, int height) {
            if (thumbnailesSize == null) {
                thumbnailesSize = new Size(width, height);
            } else if (thumbnailesSize.getWidth() == width && thumbnailesSize.getHeight() == height) {
                return;
            }
            if (currentTab != null) {
                currentTab.webView.setNeedThumbnail(thumbnailesSize);
                currentTab.webView.postInvalidate();
            }
        }
    };

    PopupMenu.OnMenuItemClickListener onMenuMoreItemClickListener = new PopupMenu.OnMenuItemClickListener() {
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            switch (item.getItemId()) {
                case R.id.miFavorites:
                    String currentPageTitle = currentTab != null ? currentTab.currentTitle : "";
                    String currentPageUrl = currentTab != null ? currentTab.currentOriginalUrl : "";
                    new FavoritesDialog(MainActivity.this, new FavoritesDialog.Callback() {
                        @Override
                        public void onFavoriteChoosen(FavoriteItem item) {
                            navigate(item.url);
                        }
                    }, currentPageTitle, currentPageUrl).show();
                    hideMenuOverlay();
                    return true;
                case R.id.miHistory:
                    startActivityForResult(
                            new Intent(MainActivity.this, HistoryActivity.class),
                            REQUEST_CODE_HISTORY_ACTIVITY);
                    hideMenuOverlay();
                    return true;
                case R.id.miSearchEngine:
                    SearchEngineConfigDialogFactory.show(MainActivity.this, searchEngineURL, prefs, true, new SearchEngineConfigDialogFactory.Callback() {
                        @Override
                        public void onDone(String url) {
                            searchEngineURL = url;
                        }
                    });
                    hideMenuOverlay();
                    return true;
                case R.id.miUserAgent:
                    hideMenuOverlay();
                    if (currentTab == null) {
                        return true;
                    }
                    String uaString = currentTab.webView.getSettings().getUserAgentString();
                    if (WebViewEx.Companion.getDefaultUAString().equals(uaString)) {
                        uaString = "";
                    }
                    UserAgentConfigDialogFactory.show(MainActivity.this, uaString, new UserAgentConfigDialogFactory.Callback() {
                        @Override
                        public void onDone(String uaString) {
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putString(USER_AGENT_PREF_KEY, uaString);
                            editor.apply();
                            for (WebTabState tab: tabsStates) {
                                if (tab.webView != null) {
                                    tab.webView.getSettings().setUserAgentString(uaString);
                                }
                            }
                            refresh();
                        }
                    });

                    return true;
                case R.id.miShortcutMenu: case R.id.miShortcutNavigateBack:
                case R.id.miShortcutNavigateHome: case R.id.miShortcutRefreshPage:
                case R.id.miShortcutVoiceSearch:
                    new ShortcutDialog(MainActivity.this,
                            ShortcutMgr.getInstance(MainActivity.this).findForMenu(item.getItemId()))
                            .show();
                    return true;
                default:
                    return false;
            }

        }
    };

    class OnTabsLoadedRunnable implements Runnable {
        private List<WebTabState> tabsStatesLoaded;

        public OnTabsLoadedRunnable(List<WebTabState> tabsStatesLoaded) {
            this.tabsStatesLoaded = tabsStatesLoaded;
        }

        @Override
        public void run() {
            Uri intentUri = getIntent().getData();
            progressBarGeneric.setVisibility(View.GONE);
            if (!running) {
                if (!tabsStatesLoaded.isEmpty()) {
                    MainActivity.this.tabsStates.addAll(tabsStatesLoaded);
                }
                return;
            }
            if (tabsStatesLoaded.isEmpty()) {
                if (intentUri == null) {
                    openInNewTab(HOME_URL);
                }
            } else {
                MainActivity.this.tabsStates.addAll(tabsStatesLoaded);
                for (int i = 0; i < tabsStatesLoaded.size(); i++) {
                    WebTabState tab = tabsStatesLoaded.get(i);
                    if (tab.selected) {
                        changeTab(tab);
                        break;
                    }
                }
            }
            if (intentUri != null) {
                openInNewTab(intentUri.toString());
            }
        }
    };

    ServiceConnection downloadsServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            DownloadService.Binder binder = (DownloadService.Binder) service;
            downloadsService = binder.getService();
            downloadsService.registerListener(downloadsServiceListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            downloadsService.unregisterListener(downloadsServiceListener);
            downloadsService = null;
        }
    };

    DownloadService.Listener downloadsServiceListener = new DownloadService.Listener() {
        @Override
        public void onDownloadUpdated(Download downloadInfo) {

        }

        @Override
        public void onDownloadError(Download downloadInfo, int responseCode, String responseMessage) {

        }

        @Override
        public void onAllDownloadsComplete() {
            if (downloadAnimation != null) {
                downloadAnimation.reset();
                ibDownloads.clearAnimation();
                downloadAnimation = null;
            }
        }
    };
}
