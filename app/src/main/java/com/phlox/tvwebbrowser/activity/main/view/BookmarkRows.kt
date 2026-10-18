package com.phlox.tvwebbrowser.activity.main.view

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator.Action
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator.Row
import com.phlox.tvwebbrowser.data.FavoritesRepository
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.HOME_PAGE
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.ROOT

/** The ways to import a file are explained in their captions */
private const val IMPORT_CHOICE_CAPTION_LINES = 4

/** The views of a row of the bookmarks screens (the TV panel, the phone screen) */
class BookmarkRowViews(val icon: ImageView, val title: TextView, val caption: TextView, val end: ImageView, val options: View)

/**
 * Shows the row in its views: an icon (tinted with [iconTint]), the title and a caption (the host of a
 * bookmark, after the name of its folder in search results), the icon at its end
 * (a chevron on folders, a check on the chosen folder) and the button of its options. The favicon of a
 * bookmark is loaded by [loadFavicon] (for urls that have one) while a placeholder is shown.
 */
fun bindBookmarkRow(row: Row, views: BookmarkRowViews, iconTint: ColorStateList, loadFavicon: (String) -> Unit) {
    val context = views.title.context
    fun setIcon(@DrawableRes icon: Int) {
        ImageViewCompat.setImageTintList(views.icon, iconTint)
        views.icon.setImageResource(icon)
    }
    fun setTexts(title: String, caption: String?, captionLines: Int = 1) {
        views.title.text = title
        views.caption.text = caption
        views.caption.isVisible = caption != null
        if (captionLines == 1) {
            views.caption.isSingleLine = true
        } else {
            views.caption.isSingleLine = false
            views.caption.maxLines = captionLines
        }
    }
    fun setEnd(@DrawableRes icon: Int) {
        views.end.isVisible = icon != 0
        if (icon != 0) views.end.setImageResource(icon)
    }
    fun setOptions(visible: Boolean, title: String) {
        views.options.isVisible = visible
        views.options.contentDescription = context.getString(R.string.options_of, title)
    }
    when (row) {
        is Row.Folder -> {
            setIcon(if (row.item == null) R.drawable.ic_home else R.drawable.ic_folder)
            setTexts(row.title, context.resources.getQuantityString(R.plurals.bookmark_count, row.count, row.count))
            setEnd(R.drawable.ic_chevron_right)
            setOptions(row.item != null, row.title)
        }
        is Row.Bookmark -> {
            val url = row.item.url ?: ""
            val title = row.item.title?.ifEmpty { null } ?: url
            val host = BookmarksNavigator.hostOf(url)
            setTexts(title, row.folderTitle?.let { context.getString(R.string.folder_and_host, it, host) } ?: host)
            setEnd(0)
            setOptions(true, title)
            setIcon(R.drawable.ic_not_available)
            if (url.isNotEmpty() && url != Config.HOME_PAGE_URL) loadFavicon(url)
        }
        is Row.Target -> {
            setIcon(when (row.id) {
                ROOT -> R.drawable.ic_favorites
                HOME_PAGE -> R.drawable.ic_home
                else -> R.drawable.ic_folder
            })
            setTexts(row.title, null)
            setEnd(if (row.chosen) R.drawable.ic_check else 0)
            setOptions(false, row.title)
        }
        Row.NewFolder -> {
            setIcon(R.drawable.ic_create_new_folder)
            setTexts(context.getString(R.string.new_folder), null)
            setEnd(0)
            setOptions(false, "")
        }
        is Row.ImportFile -> {
            setIcon(R.drawable.ic_file)
            setTexts(row.file.name, row.caption.ifEmpty { null })
            setEnd(0)
            setOptions(false, row.file.name)
        }
        Row.OtherFiles -> {
            setIcon(R.drawable.ic_folder)
            setTexts(context.getString(R.string.other_files), context.getString(R.string.other_files_caption))
            setEnd(R.drawable.ic_chevron_right)
            setOptions(false, "")
        }
        is Row.ImportChoice -> {
            setIcon(when (row.mode) {
                FavoritesRepository.ImportMode.NEW_FOLDER -> R.drawable.ic_create_new_folder
                FavoritesRepository.ImportMode.MERGE -> R.drawable.ic_move_to_folder
            })
            setTexts(row.title, row.caption, IMPORT_CHOICE_CAPTION_LINES)
            setEnd(0)
            setOptions(false, row.title)
        }
    }
}

@get:DrawableRes
val Action.icon: Int get() = when (this) {
    Action.OPEN_IN_NEW_TAB -> R.drawable.ic_open_in_new
    Action.EDIT, Action.RENAME -> R.drawable.ic_edit
    Action.MOVE_TO_FOLDER -> R.drawable.ic_move_to_folder
    Action.MOVE_UP -> R.drawable.ic_arrow_up
    Action.MOVE_DOWN -> R.drawable.ic_arrow_down
    Action.DELETE -> R.drawable.ic_delete
}

@get:StringRes
val Action.label: Int get() = when (this) {
    Action.OPEN_IN_NEW_TAB -> R.string.open_in_new_tab
    Action.EDIT -> R.string.edit
    Action.RENAME -> R.string.rename
    Action.MOVE_TO_FOLDER -> R.string.move_to_folder
    Action.MOVE_UP -> R.string.move_up
    Action.MOVE_DOWN -> R.string.move_down
    Action.DELETE -> R.string.delete
}
