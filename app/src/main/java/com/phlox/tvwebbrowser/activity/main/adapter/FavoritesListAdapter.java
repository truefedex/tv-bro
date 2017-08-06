package com.phlox.tvwebbrowser.activity.main.adapter;

import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.phlox.tvwebbrowser.activity.main.view.FavoriteItemView;
import com.phlox.tvwebbrowser.model.FavoriteItem;

import java.util.List;

/**
 * Created by PDT on 13.09.2016.
 */
public class FavoritesListAdapter extends BaseAdapter {
    private List<FavoriteItem> favorites;
    private FavoriteItemView.Listener itemsListener;
    private boolean editMode = false;

    public FavoritesListAdapter(List<FavoriteItem> favorites, FavoriteItemView.Listener itemsListener) {
        this.favorites = favorites;
        this.itemsListener = itemsListener;
    }

    @Override
    public int getCount() {
        return favorites.size();
    }

    @Override
    public Object getItem(int i) {
        return favorites.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public View getView(int i, View convertView, ViewGroup viewGroup) {
        FavoriteItemView view;
        if (convertView != null) {
            view = (FavoriteItemView) convertView;
        } else {
            view = new FavoriteItemView(viewGroup.getContext(), itemsListener);
        }
        view.bind(favorites.get(i), editMode);
        return view;
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        notifyDataSetChanged();
    }

    public boolean isEditMode() {
        return editMode;
    }
}
