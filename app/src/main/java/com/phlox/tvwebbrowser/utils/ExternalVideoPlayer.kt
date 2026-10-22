package com.phlox.tvwebbrowser.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
import com.phlox.tvwebbrowser.webengine.PageVideo

/**
 * Opens a [PageVideo] in a video player app, the one the user chooses in the dialog of Android. The
 * player gets the headers the page's requests had (many servers give their videos only with them): the
 * Referer (the frame of the video), the User-Agent of the engine and the cookies of the address, in the
 * extras players read ([Browser.EXTRA_HEADERS]; `headers`, a list of names and values, of MX Player and
 * Just Player), and the title.
 */
object ExternalVideoPlayer {
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_HEADERS_LIST = "headers"

    /** @return false if no player app is installed */
    fun open(context: Context, video: PageVideo, cookies: String?): Boolean =
        DownloadFileActions.start(context, intent(video, cookies))

    fun intent(video: PageVideo, cookies: String?): Intent {
        val headers = headers(video, cookies)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(video.url), video.mimeType)
            putExtra(EXTRA_TITLE, title(video))
            if (headers.isNotEmpty()) {
                putExtra(Browser.EXTRA_HEADERS, Bundle().apply { headers.forEach { (name, value) -> putString(name, value) } })
                putExtra(EXTRA_HEADERS_LIST, headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
            }
        }
    }

    fun headers(video: PageVideo, cookies: String?): Map<String, String> = buildMap {
        video.pageUrl?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        video.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        cookies?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    /** The title of the frame, else the name of the file */
    fun title(video: PageVideo): String =
        video.title?.takeIf { it.isNotBlank() } ?: fileName(video.url)

    fun fileName(url: String): String {
        val name = Uri.parse(url).lastPathSegment
        return if (name.isNullOrBlank()) Uri.parse(url).host ?: url else name
    }
}
