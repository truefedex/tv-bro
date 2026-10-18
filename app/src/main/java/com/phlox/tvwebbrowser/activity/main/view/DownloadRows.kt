package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.DownloadRowText
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Action
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Kind
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.widgets.datedlist.DatedRow
import com.phlox.tvwebbrowser.widgets.datedlist.startOfDay
import java.text.DateFormat
import java.util.Date

/** The views of a row of the downloads screens (the TV panel and screen, the phone page) */
class DownloadRowViews(
    val icon: ImageView,
    val title: TextView,
    val caption: TextView,
    val progress: ProgressBar,
    /** The buttons for [DownloadsNavigator.rowButtonsOf], the unused ones are hidden */
    val buttons: List<ImageButton>,
    /** The button of the options, null if the row has none (the phone rows in progress) */
    val options: View?
)

/**
 * Shows the [item] in its row: the icon of its kind (an error sign if it failed), its name, the caption
 * of [text] and the progress of a download in progress or paused; the [buttons][DownloadRowViews.buttons]
 * do what [onAction] is given.
 */
fun bindDownloadRow(item: DownloadItem, navigator: DownloadsNavigator, text: DownloadRowText, views: DownloadRowViews,
                    withHost: Boolean, withDay: Boolean, onAction: (Action) -> Unit) {
    val context = views.title.context
    views.icon.setImageResource(if (item.status == Download.Status.FAILED) R.drawable.ic_error
        else DownloadsNavigator.kindOf(item).icon)
    views.title.text = item.filename
    views.caption.text = text.caption(item, withHost, withDay)
    views.progress.isVisible = item.isActive
    if (item.isActive) {
        val known = item.size > 0
        views.progress.isIndeterminate = !known && item.status == Download.Status.IN_PROGRESS
        if (known) views.progress.progress = (item.bytesReceived * views.progress.max / item.size).toInt()
        else if (!views.progress.isIndeterminate) views.progress.progress = 0
    }
    val actions = navigator.rowButtonsOf(item)
    for ((i, button) in views.buttons.withIndex()) {
        val action = actions.getOrNull(i)
        button.isVisible = action != null
        if (action == null) continue
        button.setImageResource(action.icon)
        button.contentDescription = context.getString(action.buttonLabel)
        button.setOnClickListener { onAction(action) }
    }
    views.options?.contentDescription = context.getString(R.string.options_of, item.filename)
}

/** The title of a section of all downloads: In progress, Today, Yesterday or the date */
fun sectionTitle(context: Context, day: Long, now: Long = System.currentTimeMillis()): String {
    val today = startOfDay(now)
    return when (day) {
        DatedRow.PINNED_GROUP -> context.getString(R.string.in_progress)
        today -> context.getString(R.string.today)
        startOfDay(today - 1) -> context.getString(R.string.yesterday)
        else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(day))
    }
}

@get:DrawableRes
val Kind.icon: Int get() = when (this) {
    Kind.APP -> R.drawable.ic_app
    Kind.VIDEO -> R.drawable.ic_video
    Kind.IMAGE -> R.drawable.ic_image
    Kind.DOCUMENT -> R.drawable.ic_document
    Kind.OTHER -> R.drawable.ic_file
}

/** The name of the filter; null is All */
@StringRes
fun filterLabel(kind: Kind?): Int = when (kind) {
    null -> R.string.filter_all
    Kind.APP -> R.string.filter_apps
    Kind.VIDEO -> R.string.filter_video
    Kind.IMAGE -> R.string.filter_images
    Kind.DOCUMENT -> R.string.filter_documents
    Kind.OTHER -> R.string.filter_other
}

@get:DrawableRes
val Action.icon: Int get() = when (this) {
    Action.OPEN -> R.drawable.ic_open_in_new
    Action.INSTALL -> R.drawable.ic_app
    Action.OPEN_FOLDER -> R.drawable.ic_folder
    Action.COPY_LINK -> R.drawable.ic_link
    Action.SHARE -> R.drawable.ic_share
    Action.SHOW_IN_ALL -> R.drawable.ic_downloads
    Action.DOWNLOAD_AGAIN -> R.drawable.ic_refresh
    Action.PAUSE -> R.drawable.ic_pause
    Action.RESUME -> R.drawable.ic_play
    Action.CANCEL -> R.drawable.ic_close
    Action.REMOVE_FROM_LIST -> R.drawable.ic_close
    Action.DELETE_FILE -> R.drawable.ic_delete
}

@get:StringRes
val Action.label: Int get() = when (this) {
    Action.OPEN -> R.string.open
    Action.INSTALL -> R.string.install
    Action.OPEN_FOLDER -> R.string.open_downloads_folder
    Action.COPY_LINK -> R.string.copy_link
    Action.SHARE -> R.string.share
    Action.SHOW_IN_ALL -> R.string.show_in_all_downloads
    Action.DOWNLOAD_AGAIN -> R.string.download_again
    Action.PAUSE -> R.string.pause
    Action.RESUME -> R.string.resume
    Action.CANCEL -> R.string.cancel
    Action.REMOVE_FROM_LIST -> R.string.remove_from_list
    Action.DELETE_FILE -> R.string.delete_file
}

/** The description of the button of the action on a row */
@get:StringRes
private val Action.buttonLabel: Int get() = if (this == Action.CANCEL) R.string.cancel_download else label

/** The rows of downloads are the same download while their id is */
object DownloadItemDiff : DiffUtil.ItemCallback<DownloadItem>() {
    override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem) = oldItem == newItem
}
