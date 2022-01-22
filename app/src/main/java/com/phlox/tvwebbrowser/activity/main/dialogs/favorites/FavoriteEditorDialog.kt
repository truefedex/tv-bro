package com.phlox.tvwebbrowser.activity.main.dialogs.favorites

import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView

import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.FavoriteItem

/**
 * Created by PDT on 13.09.2016.
 */
class FavoriteEditorDialog(context: Context, private val callback: Callback, private val item: FavoriteItem) : Dialog(context) {
    private val tvTitle: TextView
    private val tvUrl: TextView
    private val etTitle: EditText
    private val etUrl: EditText
    private val ibTitle: ImageButton
    private val ibUrl: ImageButton
    private val btnDone: Button
    private val btnCancel: Button

    interface Callback {
        fun onDone(item: FavoriteItem)
    }

    init {
        setCancelable(true)
        setTitle(if (item.id == 0L) R.string.new_bookmark else R.string.edit)
        setContentView(R.layout.dialog_new_favorite_item)
        tvTitle = findViewById<View>(R.id.tvTitle) as TextView
        tvUrl = findViewById<View>(R.id.tvUrl) as TextView
        etTitle = findViewById<View>(R.id.etTitle) as EditText
        etUrl = findViewById<View>(R.id.etUrl) as EditText
        ibTitle = findViewById<View>(R.id.ibTitle) as ImageButton
        ibUrl = findViewById<View>(R.id.ibUrl) as ImageButton
        btnDone = findViewById<View>(R.id.btnDone) as Button
        btnCancel = findViewById<View>(R.id.btnCancel) as Button

        ibTitle.setOnClickListener {
            ibTitle.visibility = View.GONE
            tvTitle.visibility = View.GONE
            etTitle.visibility = View.VISIBLE
            etTitle.requestFocus()
        }

        ibUrl.setOnClickListener {
            ibUrl.visibility = View.GONE
            tvUrl.visibility = View.GONE
            etUrl.visibility = View.VISIBLE
            etUrl.requestFocus()
        }

        btnDone.setOnClickListener {
            this@FavoriteEditorDialog.item.title = etTitle.text.toString()
            this@FavoriteEditorDialog.item.url = etUrl.text.toString()
            callback.onDone(this@FavoriteEditorDialog.item)
            dismiss()
        }
        btnCancel.setOnClickListener { dismiss() }

        tvTitle.text = item.title
        etTitle.setText(item.title)
        tvUrl.text = item.url
        etUrl.setText(item.url)
    }
}
