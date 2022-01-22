package com.phlox.tvwebbrowser.activity.main.dialogs.favorites

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter

import com.phlox.tvwebbrowser.model.FavoriteItem

/**
 * Created by PDT on 13.09.2016.
 */
class FavoritesListAdapter(private val favorites: List<FavoriteItem>, private val itemsListener: FavoriteItemView.Listener) : BaseAdapter() {
    var isEditMode = false
        set(editMode) {
            field = editMode
            notifyDataSetChanged()
        }

    override fun getCount(): Int {
        return favorites.size
    }

    override fun getItem(i: Int): Any {
        return favorites[i]
    }

    override fun getItemId(i: Int): Long {
        return i.toLong()
    }

    override fun getView(i: Int, convertView: View?, viewGroup: ViewGroup): View {
        val view: FavoriteItemView
        if (convertView != null) {
            view = convertView as FavoriteItemView
        } else {
            view = FavoriteItemView(viewGroup.context)
            view.listener = itemsListener
        }
        view.bind(favorites[i], isEditMode)
        return view
    }
}
