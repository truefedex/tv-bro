package com.phlox.tvwebbrowser.activity.main.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.HistoryNavigator
import com.phlox.tvwebbrowser.activity.main.HistoryNavigator.Screen
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.utils.themeColor
import java.text.DateFormat
import java.util.Date

/**
 * The views of a history row. The TV row has the [time] and the [check] box in their own places; the
 * phone row starts its caption with the time and shows the check box in place of the favicon.
 */
class HistoryRowViews(
    val main: View,
    val check: ImageView?,
    val time: TextView?,
    val icon: ImageView,
    val title: TextView,
    val caption: TextView,
    val options: View
)

/** Shows the [item] of the [screen] in a row: the words searched marked, chosen in the selection mode */
class HistoryRowBinder(private val context: Context) {
    private val timeFormat: DateFormat = android.text.format.DateFormat.getTimeFormat(context)
    private val iconTint = ColorStateList.valueOf(context.themeColor(R.attr.tbColorDsIcon))
    private val markColor = context.themeColor(R.attr.tbColorDsControlSelected)

    /**
     * @param newIcon true when the row does not show the favicon of the [item] yet: [loadFavicon] is
     * called for it, while a placeholder is shown
     * @return whether the icon of the row is the favicon (not the check box of the phone row)
     */
    fun bind(item: HistoryItem, screen: Screen, views: HistoryRowViews, newIcon: Boolean, loadFavicon: (String) -> Unit): Boolean {
        val selected = screen.isSelected(item)
        val title = titleOf(item)
        val time = timeFormat.format(Date(item.time))
        val address = HistoryNavigator.addressOf(item, screen.words)
        views.main.isActivated = selected
        views.title.text = mark(title, screen.words)
        views.options.isVisible = !screen.selecting
        views.options.contentDescription = context.getString(R.string.options_of, title)
        if (views.time != null) {
            views.time.text = time
            views.caption.text = mark(address, screen.words)
        } else {
            views.caption.text = mark(context.getString(R.string.history_time_and_address, time, address), screen.words,
                skip = time.length)
        }
        val checkIcon = if (selected) R.drawable.ic_check_box else R.drawable.ic_check_box_blank
        if (views.check != null) {
            views.check.isVisible = screen.selecting
            views.check.setImageResource(checkIcon)
        }
        if (views.check == null && screen.selecting) {
            ImageViewCompat.setImageTintList(views.icon, iconTint)
            views.icon.setImageResource(checkIcon)
            return false
        }
        if (newIcon) {
            ImageViewCompat.setImageTintList(views.icon, iconTint)
            views.icon.setImageResource(R.drawable.ic_not_available)
            loadFavicon(item.url)
        }
        return true
    }

    /** The [text] with the [words] marked, from [skip] on */
    private fun mark(text: String, words: List<String>, skip: Int = 0): CharSequence {
        if (words.isEmpty()) return text
        val ranges = HistoryNavigator.highlights(text.substring(skip), words)
        if (ranges.isEmpty()) return text
        val spannable = SpannableString(text)
        for (range in ranges) {
            spannable.setSpan(BackgroundColorSpan(markColor), skip + range.first, skip + range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    companion object {
        /** The title of the item, or its address if it has none */
        fun titleOf(item: HistoryItem): String = item.title.ifBlank { HistoryNavigator.withoutScheme(item.url) }
    }
}

fun copyLinkToClipboard(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(url, url))
}

/** The title of the selection mode, or of the history */
fun historyTitle(context: Context, screen: Screen): String = screen.selection?.let {
    context.resources.getQuantityString(R.plurals.items_selected, it.size, it.size)
} ?: context.getString(R.string.history)

/** The text of an empty list: no history, or nothing found */
fun historyEmptyText(context: Context, screen: Screen): String =
    context.getString(if (screen.searching) R.string.no_history_found else R.string.no_history_yet)

object HistoryItemDiff : DiffUtil.ItemCallback<HistoryItem>() {
    override fun areItemsTheSame(oldItem: HistoryItem, newItem: HistoryItem) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: HistoryItem, newItem: HistoryItem) =
        oldItem.time == newItem.time && oldItem.title == newItem.title && oldItem.url == newItem.url
}
