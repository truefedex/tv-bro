package com.phlox.tvwebbrowser.activity.main.dialogs.favorites

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.FavoriteItem

/**
 * The form of a bookmark of the home page (its name and address), an alert dialog of the app theme
 * with the fields of the bookmarks panel. Done is enabled while the address is not blank.
 */
class FavoriteEditorDialog(
    private val context: Context,
    private val callback: Callback,
    private val item: FavoriteItem
) {
    interface Callback {
        fun onDone(item: FavoriteItem)
    }

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_new_favorite_item, null)
        val etTitle = view.findViewById<EditText>(R.id.etTitle)
        val etUrl = view.findViewById<EditText>(R.id.etUrl)
        etTitle.setText(item.title)
        etUrl.setText(item.url)

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (item.id == 0L) R.string.new_bookmark else R.string.edit)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.done) { _, _ ->
                item.title = etTitle.text.toString()
                var urlStr = etUrl.text.toString().trim()
                //add https:// if url not starts with any schema
                if (!urlStr.matches(Regex("^[A-Za-z]+://.*$"))) {
                    urlStr = "https://$urlStr"
                }
                item.url = urlStr
                callback.onDone(item)
            }
            .create()
        dialog.show()

        val btnDone = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        btnDone.isEnabled = etUrl.text.isNotBlank()
        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnDone.isEnabled = !s.isNullOrBlank()
            }
        })
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && btnDone.isEnabled) {
                btnDone.performClick()
                true
            } else false
        }
    }
}
