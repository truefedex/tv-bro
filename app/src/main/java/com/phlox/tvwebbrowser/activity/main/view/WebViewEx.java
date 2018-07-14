package com.phlox.tvwebbrowser.activity.main.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.shapes.PathShape;
import android.os.Handler;
import android.os.SystemClock;
import android.renderscript.Allocation;
import android.renderscript.RenderScript;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.activity.main.MainActivity;

/**
 * Created by fedex on 12.08.16.
 */
public class WebViewEx extends WebView {
    private static final String LONG_PRESS_SCRIPT = "var element = window.TVBRO_activeElement;\n" +
            "if (element != null) {\n" +
            "  if ('A' == element.tagName) {\n" +
            "    element.protocol+'//'+element.host+element.pathname+element.search+element.hash;\n" +
            "  }\n" +
            "}";

    private Size needThumbnail = null;
    private Listener listener;

    private PopupMenu actionsMenu;
    private int lastTouchX;
    private int lastTouchY;
    public static String defaultUAString;
    public String uaString;

    public interface Listener {
        void onThumbnailReady(Bitmap thumbnail);
        void onOpenInNewTabRequested(String s);
        void onDownloadRequested(String url);
    }

    public WebViewEx(Context context) {
        super(context);
        init();
    }

    public WebViewEx(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        if (isInEditMode()) {
            return;
        }

        WebSettings browserSettings = getSettings();
        browserSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        if (defaultUAString == null) {
            defaultUAString = "TV Bro/1.0 " + browserSettings.getUserAgentString().replace("Mobile Safari", "Safari");
        }
        SharedPreferences prefs = getContext().getSharedPreferences(MainActivity.MAIN_PREFS_NAME, Context.MODE_PRIVATE);
        uaString = prefs.getString(MainActivity.USER_AGENT_PREF_KEY, "");
        if ("".equals(uaString)) {
            uaString = defaultUAString;
        }
        browserSettings.setUserAgentString(uaString);
        browserSettings.setPluginState(WebSettings.PluginState.ON_DEMAND);
        browserSettings.setJavaScriptEnabled(true);
        browserSettings.setDatabaseEnabled(true);
        browserSettings.setUseWideViewPort(true);
        /*browserSettings.setSupportZoom(true);
        browserSettings.setBuiltInZoomControls(true);
        browserSettings.setDisplayZoomControls(false);*/
        browserSettings.setSaveFormData(true);
        browserSettings.setSupportZoom(true);
        browserSettings.setDomStorageEnabled(true);
        browserSettings.setAllowContentAccess(false);
        browserSettings.setAppCachePath(getContext().getCacheDir().getAbsolutePath());
        browserSettings.setAppCacheEnabled(true);
        browserSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        browserSettings.setMediaPlaybackRequiresUserGesture(false);
        browserSettings.setGeolocationEnabled(true);
        //browserSettings.setJavaScriptCanOpenWindowsAutomatically();

        setOnLongClickListener(new OnLongClickListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public boolean onLongClick(View view) {
                evaluateJavascript(LONG_PRESS_SCRIPT, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String s) {
                        if (s != null && !"null".equals(s)) {
                            suggestActions(s);
                        }
                    }
                });
                return true;
            }
        });
    }

    private void suggestActions(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        final String url = s.toLowerCase();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            final View anchor = new View(getContext());
            final FrameLayout parent = ((FrameLayout) getParent());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
            lp.setMargins(lastTouchX, lastTouchY, 0, 0);
            parent.addView(anchor, lp);
            actionsMenu = new PopupMenu(getContext(), anchor, Gravity.BOTTOM);
            final MenuItem miNewTab = actionsMenu.getMenu().add(R.string.open_in_new_tab);
            actionsMenu.getMenu().add(R.string.download);
            actionsMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem menuItem) {
                    if (menuItem == miNewTab) {
                        listener.onOpenInNewTabRequested(url);
                    } else {
                        listener.onDownloadRequested(url);
                    }
                    return true;
                }
            });

            actionsMenu.setOnDismissListener(new PopupMenu.OnDismissListener() {
                @Override
                public void onDismiss(PopupMenu popupMenu) {
                    parent.removeView(anchor);
                    actionsMenu = null;
                }
            });
            actionsMenu.show();
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setNeedThumbnail(Size needThumbnail) {
        this.needThumbnail = needThumbnail;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_UP: {
                lastTouchX = (int) event.getX();
                lastTouchY = (int) event.getY();
                break;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needThumbnail != null && listener != null) {
            renderThumbnail();
        }

        super.onDraw(canvas);
    }

    private void renderThumbnail() {
        Bitmap thumbnail = Bitmap.createBitmap(needThumbnail.getWidth(), needThumbnail.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(thumbnail);
        float scaleFactor = needThumbnail.getWidth() / (float)getWidth();
        needThumbnail = null;
        canvas.scale(scaleFactor, scaleFactor);
        super.draw(canvas);
        listener.onThumbnailReady(thumbnail);
    }
}
