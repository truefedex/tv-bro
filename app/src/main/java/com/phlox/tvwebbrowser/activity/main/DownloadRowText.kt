package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.AppContext
import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.send.SendRules
import com.phlox.tvwebbrowser.widgets.datedlist.startOfDay
import java.util.Date

/** The caption under the name of a download in the downloads screens */
/** [markKids]: the caption tells the downloads of the kids profile (the main profile lists them with its own) */
class DownloadRowText(private val context: Context, private val markKids: Boolean = !AppContext.kidsProfile) {
    private val separator = " · "

    /**
     * - in progress: "18.6 MB of 48.2 MB · 2.4 MB/s · 12 s left" ("Saving the video" while a stream is packed into
     *   its file);
     * - paused: "Paused · 0.7 GB of 6.0 GB" ("Paused: connection lost · ..." when it was not the user);
     * - complete: "601 KB · 15:18 · site.com";
     * - failed: "Failed: connection lost · 11:02 · site.com", cancelled: "Cancelled · 18:44 · site.com";
     * - of the kids profile, in the main one: "Kids profile · 601 KB · 15:18 · site.com".
     * @param withHost adds the host of the page the download came from (else of its url)
     * @param withDay tells the day of downloads older than today (the lists grouped by days do not need it)
     */
    fun caption(item: DownloadItem, withHost: Boolean, withDay: Boolean, now: Long = System.currentTimeMillis()): String {
        val parts = ArrayList<String>()
        if (markKids && item.kids) parts += context.getString(R.string.download_of_kids)
        when (item.status) {
            //a stream is packed into its file when all its segments are there
            Download.Status.IN_PROGRESS -> if (item.isStream && item.size > 0 && item.bytesReceived >= item.size) {
                parts += context.getString(R.string.download_saving_video)
            } else {
                parts += progress(item)
                if (item.bytesPerSecond > 0) {
                    parts += context.getString(R.string.download_speed, size(item.bytesPerSecond))
                    if (item.size > item.bytesReceived) {
                        parts += timeLeft((item.size - item.bytesReceived) / item.bytesPerSecond)
                    }
                }
            }
            Download.Status.PAUSED -> {
                //paused by the user, or when the connection was lost
                parts += if (item.error == null) context.getString(R.string.download_paused)
                    else context.getString(R.string.download_paused_reason, reason(item.error))
                parts += progress(item)
            }
            else -> {
                parts += when (item.status) {
                    Download.Status.FAILED -> context.getString(R.string.download_failed, reason(item.error))
                    Download.Status.CANCELLED -> context.getString(R.string.download_cancelled)
                    else -> size(item.size)
                }
                parts += time(item.time, withDay, now)
            }
        }
        if (withHost) hostOf(item)?.let { parts += it }
        return parts.joinToString(separator)
    }

    /**
     * "18.6 MB of 48.2 MB", or what came for an unknown size; "18.6 MB of about 1.2 GB" for a stream, whose size is
     * estimated
     */
    private fun progress(item: DownloadItem): String = when {
        item.size <= 0 -> size(item.bytesReceived)
        item.isStream -> context.getString(R.string.downloaded_of_about, size(item.bytesReceived), size(item.size))
        else -> context.getString(R.string.downloaded_of, size(item.bytesReceived), size(item.size))
    }

    fun size(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    private fun time(time: Long, withDay: Boolean, now: Long): String {
        val clock = DateFormat.getTimeFormat(context).format(Date(time))
        if (!withDay) return clock
        val today = startOfDay(now)
        return when {
            time >= today -> clock
            time >= today - DateUtils.DAY_IN_MILLIS -> context.getString(R.string.yesterday)
            else -> DateFormat.getMediumDateFormat(context).format(Date(time))
        }
    }

    private fun timeLeft(seconds: Long): String = when {
        seconds < 60 -> context.getString(R.string.time_left_seconds, seconds)
        seconds < 3600 -> context.getString(R.string.time_left_minutes, (seconds + 59) / 60)
        else -> context.getString(R.string.time_left_hours, seconds / 3600, (seconds % 3600) / 60)
    }

    fun reason(error: String?): String = when (error) {
        null -> context.getString(R.string.download_error_unknown)
        Download.Error.INTERRUPTED -> context.getString(R.string.download_error_interrupted)
        Download.Error.CONNECTION -> context.getString(R.string.download_error_connection)
        Download.Error.STORAGE -> context.getString(R.string.download_error_storage)
        Download.Error.TIMEOUT -> context.getString(R.string.download_error_timeout)
        Download.Error.LIVE -> context.getString(R.string.download_error_live)
        Download.Error.PROTECTED -> context.getString(R.string.download_error_protected)
        Download.Error.FORMAT -> context.getString(R.string.download_error_format)
        else -> error
    }

    companion object {
        /**
         * The host of the page the download came from, else of its url (none for blob and data urls); the name
         * of the device for a file another device sent
         */
        fun hostOf(item: DownloadItem): String? = SendRules.senderOfFileUrl(item.url)
            ?: item.referer?.let { hostOf(it) } ?: hostOf(item.url)

        /** The host without www, null for urls without one (about:blank, blob:, data:) */
        private fun hostOf(url: String): String? = try {
            Uri.parse(url).host?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
