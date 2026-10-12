package com.phlox.tvwebbrowser.activity.main.view.bookmarks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewBookmarkItemBinding
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BookmarksAdapter(private val listener: Listener) : RecyclerView.Adapter<BookmarksAdapter.BookmarkViewHolder>() {

    interface Listener {
        fun onBookmarkClick(item: FavoriteItem)
        fun onBookmarkLongClick(item: FavoriteItem)
    }

    private val items = ArrayList<FavoriteItem>()

    fun setItems(newItems: List<FavoriteItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_bookmark_item, parent, false)
        return BookmarkViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class BookmarkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val vb = ViewBookmarkItemBinding.bind(itemView)

        fun bind(item: FavoriteItem) {
            vb.tvTitle.text = item.title ?: item.url
            vb.tvUrl.text = item.url
            vb.ivFavicon.setImageResource(R.drawable.ic_not_available)

            val url = item.url
            if (url != null && !Config.isHomePageUrl(url)) {
                val scope = (itemView.activity as AppCompatActivity).lifecycleScope
                scope.launch(Dispatchers.Main) {
                    val favicon = FaviconsPool.get(url)
                    if (url != item.url) return@launch //url was changed while loading favicon
                    if (!itemView.isAttachedToWindow) return@launch
                    favicon?.let {
                        vb.ivFavicon.setImageBitmap(it)
                    } ?: run {
                        vb.ivFavicon.setImageResource(R.drawable.ic_not_available)
                    }
                }
            }

            itemView.setOnClickListener { listener.onBookmarkClick(item) }
            itemView.setOnLongClickListener {
                listener.onBookmarkLongClick(item)
                true
            }
        }
    }
}
