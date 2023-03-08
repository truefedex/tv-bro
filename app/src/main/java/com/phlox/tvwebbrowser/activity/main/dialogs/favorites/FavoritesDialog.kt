package com.phlox.tvwebbrowser.activity.main.dialogs.favorites

import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.*
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

/**
 * Created by PDT on 09.09.2016.
 */
class FavoritesDialog(context: Context, val scope: CoroutineScope, private val callback: Callback, private val currentPageTitle: String?, private val currentPageUrl: String?) : Dialog(context), FavoriteItemView.Listener {
    private var items: MutableList<FavoriteItem> = ArrayList()
    private val adapter = FavoritesListAdapter(items, this)

    private val tvPlaceholder: TextView
    private val listView: ListView
    private val btnAdd: Button
    private val btnEdit: Button
    private val pbLoading: ProgressBar

    interface Callback {
        fun onFavoriteChoosen(item: FavoriteItem?)
    }

    init {
        setCancelable(true)
        setContentView(R.layout.dialog_favorites)
        setTitle(R.string.bookmarks)

        tvPlaceholder = findViewById<View>(R.id.tvPlaceholder) as TextView
        listView = findViewById<View>(R.id.listView) as ListView
        btnAdd = findViewById<View>(R.id.btnAdd) as Button
        btnEdit = findViewById<View>(R.id.btnEdit) as Button
        pbLoading = findViewById<View>(R.id.pbLoading) as ProgressBar

        btnAdd.setOnClickListener { showAddItemDialog() }

        btnEdit.setOnClickListener {
            adapter.isEditMode = !adapter.isEditMode
            btnEdit.setText(if (adapter.isEditMode) R.string.done else R.string.edit)
            listView.itemsCanFocus = adapter.isEditMode
        }

        listView.onItemClickListener = AdapterView.OnItemClickListener { adapterView, view, i, l ->
            val item = (view as FavoriteItemView).favorite
            if (item!!.isFolder) {

            } else {
                callback.onFavoriteChoosen(item)
                dismiss()
            }
        }

        pbLoading.visibility = View.VISIBLE
        listView.visibility = View.GONE
        tvPlaceholder.visibility = View.GONE
        listView.adapter = adapter

        scope.launch(Dispatchers.Main) {
            items.addAll(AppDatabase.db.favoritesDao().getAll())
            onItemsChanged()
            pbLoading.visibility = View.GONE
        }
    }

    private fun showAddItemDialog() {
        val newItem = FavoriteItem()
        newItem.title = currentPageTitle
        newItem.url = currentPageUrl
        FavoriteEditorDialog(context, object : FavoriteEditorDialog.Callback {
            override fun onDone(item: FavoriteItem) {
                onItemEdited(item)
            }
        }, newItem).show()
    }

    private fun onItemEdited(item: FavoriteItem) {
        pbLoading.visibility = View.VISIBLE
        listView.visibility = View.GONE
        tvPlaceholder.visibility = View.GONE
        scope.launch(Dispatchers.Main) {
            if (item.id == 0L) {
                val lastInsertRowId = AppDatabase.db.favoritesDao().insert(item)
                item.id = lastInsertRowId
                items.add(0, item)
                onItemsChanged()
            } else {
                AppDatabase.db.favoritesDao().update(item)
                onItemsChanged()
            }
        }
    }

    private fun onItemsChanged() {
        adapter.notifyDataSetChanged()
        pbLoading.visibility = View.GONE
        listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        tvPlaceholder.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDeleteClick(favorite: FavoriteItem) {
        scope.launch(Dispatchers.Main) {
            AppDatabase.db.favoritesDao().delete(favorite)
            items.remove(favorite)
            onItemsChanged()
        }
    }

    override fun onEditClick(favorite: FavoriteItem) {
        FavoriteEditorDialog(context, object : FavoriteEditorDialog.Callback {
            override fun onDone(item: FavoriteItem) {
                onItemEdited(item)
            }
        }, favorite).show()
    }
}
