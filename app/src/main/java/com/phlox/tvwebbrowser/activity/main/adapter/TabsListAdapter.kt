package com.phlox.tvwebbrowser.activity.main.adapter

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter

import com.phlox.tvwebbrowser.activity.main.view.WebTabItemView
import com.phlox.tvwebbrowser.model.WebTabState

/**
 * Created by PDT on 24.08.2016.
 */
class TabsListAdapter(private val states: List<WebTabState>, private val tabsEventsListener: WebTabItemView.Listener) : BaseAdapter() {

    override fun getCount(): Int {
        return states.size
    }

    override fun getItem(i: Int): Any {
        return states[i]
    }

    override fun getItemId(i: Int): Long {
        return i.toLong()
    }

    override fun getView(i: Int, convertView: View?, viewGroup: ViewGroup): View {
        val view: WebTabItemView
        if (convertView != null) {
            view = convertView as WebTabItemView
        } else {
            view = WebTabItemView(viewGroup.context)
            view.setListener(tabsEventsListener)
        }
        view.bindTabState(states[i])
        return view
    }
}
