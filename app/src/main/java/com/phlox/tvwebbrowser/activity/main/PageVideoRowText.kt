package com.phlox.tvwebbrowser.activity.main

import android.net.Uri
import android.text.format.DateUtils
import com.phlox.tvwebbrowser.webengine.PageVideo

/**
 * The caption of a row of the videos of the page: the kind, the server, the size and the duration (in whole seconds
 * counted down, as the seek bar and the players of sites tell them)
 */
object PageVideoRowText {
    fun caption(video: PageVideo): String = listOfNotNull(
        kind(video),
        Uri.parse(video.url).host,
        if (video.width != null && video.height != null) "${video.width}×${video.height}" else null,
        video.duration?.let { DateUtils.formatElapsedTime(it.toLong()) },
    ).joinToString(" · ")

    /**
     * The caption under the title of the built-in player: its name ([player]), the height of the video track
     * playing (null while unknown), the kind and the [site] of the page (the host of the tab, not the server of the
     * stream; null to leave it out), as "TV Bro player · 2160p · HLS · example.com"
     */
    fun playerCaption(player: String, video: PageVideo, height: Int?, site: String?): String = listOfNotNull(
        player,
        height?.let { "${it}p" },
        kind(video),
        site?.ifEmpty { null },
    ).joinToString(" · ")

    /** The caption under the title of the player of the page: its name ([player]) and the [site], "Site player · example.com" */
    fun sitePlayerCaption(player: String, site: String?): String = listOfNotNull(player, site?.ifEmpty { null }).joinToString(" · ")

    /** The name of the file of a stream, as "index.m3u8" (its server if the address has no path) */
    fun fileName(video: PageVideo): String {
        val uri = Uri.parse(video.url)
        return uri.lastPathSegment ?: uri.host ?: video.url
    }

    /**
     * The names of the files of [videos] ([fileName]), with their folder where two would have the same name (streams
     * are mostly index.m3u8 or master.m3u8): "film/index.m3u8", "promo/index.m3u8"
     */
    fun fileNames(videos: List<PageVideo>): List<String> {
        val names = videos.map { fileName(it) }
        return videos.mapIndexed { i, video ->
            if (names.count { it == names[i] } < 2) return@mapIndexed names[i]
            val segments = Uri.parse(video.url).pathSegments
            if (segments.size >= 2) segments.takeLast(2).joinToString("/") else names[i]
        }
    }

    /**
     * The caption of a stream in the choice over a video: the kind, the height, the duration (its playlist's) and the
     * server, as "HLS · 1080p · 22:32 · cdn.example"
     */
    fun choiceCaption(video: PageVideo): String = listOfNotNull(
        kind(video),
        video.height?.let { "${it}p" },
        video.duration?.let { DateUtils.formatElapsedTime(it.toLong()) },
        Uri.parse(video.url).host,
    ).joinToString(" · ")

    /** HLS, DASH, or the extension of a file (MP4); null for a file without one */
    fun kind(video: PageVideo): String? = when (video.kind) {
        PageVideo.Kind.HLS -> "HLS"
        PageVideo.Kind.DASH, PageVideo.Kind.XML -> "DASH"
        PageVideo.Kind.FILE -> Uri.parse(video.url).lastPathSegment?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() && it.length <= 4 }?.uppercase()
    }
}
