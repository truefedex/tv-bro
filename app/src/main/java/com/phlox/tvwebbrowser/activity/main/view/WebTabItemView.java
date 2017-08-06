package com.phlox.tvwebbrowser.activity.main.view;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.model.WebTabState;

/**
 * Created by PDT on 24.08.2016.
 */
public class WebTabItemView extends FrameLayout {
    private WebTabState tabState;
    private Listener listener;

    private final TextView tvTitle;
    private final ImageView ivThumbnail;
    private final LinearLayout llContainer;
    private final Button btnClose;

    public interface Listener {
        void onTabSelected(WebTabState tab);
        void onTabDeleteClicked(WebTabState tab);
        void onNeededThumbnailSizeCalculated(int width, int height);
    }

    public WebTabItemView(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.view_tab_item, this);
        tvTitle = (TextView) findViewById(R.id.tvTitle);
        ivThumbnail = (ImageView) findViewById(R.id.ivThumbnail);
        llContainer = (LinearLayout) findViewById(R.id.llContainer);
        btnClose = (Button) findViewById(R.id.btnClose);

        llContainer.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.onTabSelected(tabState);
            }
        });

        btnClose.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.onTabDeleteClicked(tabState);
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void bindTabState(WebTabState tabState) {
        this.tabState = tabState;
        tvTitle.setText(TextUtils.isEmpty(tabState.currentTitle) ?
                        Uri.parse(tabState.currentOriginalUrl).getHost() :
                        tabState.currentTitle);
        if (tabState.thumbnail != null) {
            ivThumbnail.setImageBitmap(tabState.thumbnail);
        } else {
            ivThumbnail.setImageResource(android.R.color.transparent);
        }
        llContainer.setBackgroundResource(tabState.selected ?
                R.drawable.selected_tab_button_bg_selector :
                R.drawable.tab_button_bg_selector);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int calculatedHeightSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec) * 4 / 5, MeasureSpec.getMode(widthMeasureSpec));
        super.onMeasure(widthMeasureSpec, calculatedHeightSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        listener.onNeededThumbnailSizeCalculated(ivThumbnail.getWidth(), ivThumbnail.getHeight());
    }
}
