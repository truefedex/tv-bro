package com.phlox.tvwebbrowser.activity.main

import android.net.Uri
import android.text.format.DateUtils
import com.phlox.tvwebbrowser.webengine.PageVideo
import kotlin.math.roundToLong

/** The caption of a row of the videos of the page: the kind, the server, the size and the duration */
object PageVideoRowText {
    fun caption(video: PageVideo): String = listOfNotNull(
        kind(video),
        Uri.parse(video.url).host,
        if (video.width != null && video.height != null) "${video.width}×${video.height}" else null,
        video.duration?.let { DateUtils.formatElapsedTime(it.roundToLong()) },
    ).joinToString(" · ")

    /**
     * The caption under the title of the built-in player: its name ([player]), the height of the video track
     * playing (null while unknown), the kind and the server, as "Built-in player · 2160p · HLS · example.com"
     */
    fun playerCaption(player: String, video: PageVideo, height: Int?): String = listOfNotNull(
        player,
        height?.let { "${it}p" },
        kind(video),
        Uri.parse(video.url).host,
    ).joinToString(" · ")

    /** HLS, DASH, or the extension of a file (MP4); null for a file without one */
    fun kind(video: PageVideo): String? = when (video.kind) {
        PageVideo.Kind.HLS -> "HLS"
        PageVideo.Kind.DASH -> "DASH"
        PageVideo.Kind.FILE -> Uri.parse(video.url).lastPathSegment?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() && it.length <= 4 }?.uppercase()
    }
}
