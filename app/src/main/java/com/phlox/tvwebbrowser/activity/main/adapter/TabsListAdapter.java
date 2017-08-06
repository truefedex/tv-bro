package com.phlox.tvwebbrowser.activity.main.adapter;

import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.phlox.tvwebbrowser.activity.main.view.WebTabItemView;
import com.phlox.tvwebbrowser.model.WebTabState;

import java.util.List;

/**
 * Created by PDT on 24.08.2016.
 */
public class TabsListAdapter extends BaseAdapter {
    private List<WebTabState> states;
    private WebTabItemView.Listener tabsEventsListener;

    public TabsListAdapter(List<WebTabState> states, WebTabItemView.Listener tabsEventsListener) {
        this.states = states;
        this.tabsEventsListener = tabsEventsListener;
    }

    @Override
    public int getCount() {
        return states.size();
    }

    @Override
    public Object getItem(int i) {
        return states.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public View getView(int i, View convertView, ViewGroup viewGroup) {
        WebTabItemView view;
        if (convertView != null) {
            view = (WebTabItemView) convertView;
        } else {
            view = new WebTabItemView(viewGroup.getContext());
            view.setListener(tabsEventsListener);
        }
        view.bindTabState(states.get(i));
        return view;
    }
}
