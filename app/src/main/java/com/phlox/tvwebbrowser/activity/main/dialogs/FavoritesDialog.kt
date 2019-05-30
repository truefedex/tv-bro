package com.phlox.tvwebbrowser.activity.main.dialogs

import android.app.Dialog
import android.content.Context
import android.database.SQLException
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

import com.phlox.asql.ASQL
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.adapter.FavoritesListAdapter
import com.phlox.tvwebbrowser.activity.main.view.FavoriteItemView
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.utils.Utils

import java.util.ArrayList

/**
 * Created by PDT on 09.09.2016.
 */
class FavoritesDialog(context: Context, private val callback: Callback, private val currentPageTitle: String?, private val currentPageUrl: String?) : Dialog(context), FavoriteItemView.Listener {
    private var items: MutableList<FavoriteItem> = ArrayList()
    private val adapter: FavoritesListAdapter = FavoritesListAdapter(items, this)
    private val asql: ASQL

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

        btnEdit.setOnClickListener { adapter.isEditMode = !adapter.isEditMode }

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

        asql = ASQL.getDefault(getContext())

        asql.queryAll(FavoriteItem::class.java, "SELECT * FROM favorites WHERE parent=0 ORDER BY id DESC", ASQL.ResultCallback { result, error ->
            pbLoading.visibility = View.GONE
            if (result != null) {
                items.addAll(result)
                onItemsChanged()
            } else {
                items = ArrayList()
                Utils.showToast(getContext(), R.string.error)
                dismiss()
            }
        })

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
        if (item.id == 0L) {
            asql.execInsert("INSERT INTO favorites (title, url, parent) VALUES (:title, :url, :parent)", item) { lastInsertRowId, exception ->
                if (exception != null) {
                    Utils.showToast(context, R.string.error)
                } else {
                    item.id = lastInsertRowId
                    items.add(0, item)
                    onItemsChanged()
                }
            }
        } else {
            asql.execUpdateDelete("UPDATE favorites SET title=:title, url=:url, parent=:parent WHERE id=:id", item) { affectedRowsCount, exception ->
                if (exception != null || affectedRowsCount == 0) {
                    Utils.showToast(context, R.string.error)
                } else {
                    onItemsChanged()
                }
            }
        }

    }

    private fun onItemsChanged() {
        adapter.notifyDataSetChanged()
        pbLoading.visibility = View.GONE
        listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        tvPlaceholder.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDeleteClick(favorite: FavoriteItem?) {
        asql.execUpdateDelete("DELETE FROM favorites WHERE id=:id", favorite) { affectedRowsCount, exception ->
            if (exception != null || affectedRowsCount == 0) {
                Utils.showToast(context, R.string.error)
            } else {
                items.remove(favorite)
                onItemsChanged()
            }
        }
    }

    override fun onEditClick(favorite: FavoriteItem?) {
        FavoriteEditorDialog(context, object : FavoriteEditorDialog.Callback {
            override fun onDone(item: FavoriteItem) {
                onItemEdited(item)
            }
        }, favorite!!).show()
    }
}
