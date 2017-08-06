package com.phlox.tvwebbrowser.activity.main.view;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.model.FavoriteItem;

/**
 * Created by PDT on 13.09.2016.
 */
public class FavoriteItemView extends FrameLayout {
    private FavoriteItem favorite;
    private Listener listener;

    private ImageButton ibDelete;
    private TextView tvTitle;
    private TextView tvUrl;
    private ImageView ivArrow;
    private LinearLayout llContent;

    public interface Listener {
        void onDeleteClick(FavoriteItem favorite);
        void onEditClick(FavoriteItem favorite);
    }

    public FavoriteItemView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.view_favorite_item, this);
        ibDelete = (ImageButton) findViewById(R.id.ibDelete);
        tvTitle = (TextView) findViewById(R.id.tvTitle);
        tvUrl = (TextView) findViewById(R.id.tvUrl);
        ivArrow = (ImageView) findViewById(R.id.ivArrow);
        llContent = (LinearLayout) findViewById(R.id.llContent);

        ibDelete.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.onDeleteClick(favorite);
            }
        });

        llContent.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.onEditClick(favorite);
            }
        });
    }

    public void bind(FavoriteItem favorite, boolean editMode) {
        this.favorite = favorite;
        ibDelete.setVisibility(editMode ? VISIBLE : GONE);
        llContent.setClickable(editMode);
        llContent.setFocusable(editMode);
        ivArrow.setVisibility(favorite.isFolder() ? VISIBLE : GONE);
        tvTitle.setText(favorite.title);
        tvUrl.setText(favorite.url);
    }

    public FavoriteItem getFavorite() {
        return favorite;
    }
}
