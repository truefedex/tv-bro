package com.phlox.tvwebbrowser.activity.main.view.bookmarks

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.RelativeLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewBookmarksBinding
import com.phlox.tvwebbrowser.model.FavoriteItem

class BookmarksPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {

    private val vb = ViewBookmarksBinding.inflate(LayoutInflater.from(context), this)

    private val adapterListener = object : BookmarksAdapter.Listener {
        override fun onBookmarkClick(item: FavoriteItem) {
            listener?.onBookmarkSelected(item)
        }

        override fun onBookmarkLongClick(item: FavoriteItem) {
            showBookmarkOptions(item)
        }
    }

    private val adapter = BookmarksAdapter(adapterListener)

    interface Listener {
        /** The first row was activated: add the current page to the bookmarks or remove it again. */
        fun onToggleThisPageBookmark()
        fun onBookmarkSelected(item: FavoriteItem)
        fun onEditBookmark(item: FavoriteItem)
        fun onDeleteBookmark(item: FavoriteItem)
    }

    var listener: Listener? = null

    init {
        init()
    }

    private fun init() {
        if (isInEditMode) return
        vb.rvBookmarks.layoutManager = LinearLayoutManager(context)
        vb.rvBookmarks.adapter = adapter
        vb.llAddThisPage.setOnClickListener { listener?.onToggleThisPageBookmark() }
    }

    /**
     * Shows the first entry only when [url] is a real web page (not the home page). The entry reads
     * "Add this page" normally, or "Remove from list" with a filled star when the page is already
     * bookmarked.
     */
    fun setCurrentPage(url: String?, bookmarked: Boolean) {
        val canAdd = url != null && url.isNotBlank() &&
                !Config.isHomePageUrl(url) && url.startsWith("http", true)
        vb.llAddThisPage.isVisible = canAdd
        if (!canAdd) return
        vb.tvAddThisPage.setText(if (bookmarked) R.string.remove_from_list else R.string.add_this_page)
        vb.ivAddThisPageIcon.setImageResource(
            if (bookmarked) R.drawable.ic_star_grey_900_36dp
            else R.drawable.ic_star_border_grey_900_36dp
        )
    }

    fun setBookmarks(items: List<FavoriteItem>) {
        adapter.setItems(items)
        vb.tvNoBookmarks.isVisible = items.isEmpty()
    }

    private fun showBookmarkOptions(item: FavoriteItem) {
        AlertDialog.Builder(context)
            .setTitle(item.title ?: item.url)
            .setItems(arrayOf(context.getString(R.string.edit), context.getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> listener?.onEditBookmark(item)
                    1 -> listener?.onDeleteBookmark(item)
                }
            }
            .show()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        if (gainFocus) {
            if (vb.llAddThisPage.isVisible) {
                vb.llAddThisPage.requestFocus()
            } else if (vb.rvBookmarks.childCount > 0) {
                vb.rvBookmarks.getChildAt(0).requestFocus()
            }
        } else {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        }
    }
}
