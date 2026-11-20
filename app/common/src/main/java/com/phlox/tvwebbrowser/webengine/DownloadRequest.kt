package com.phlox.tvwebbrowser.webengine

import android.webkit.MimeTypeMap
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.StreamSpec
import java.io.InputStream

/**
 * A file the user or the page wants to download.
 */
data class DownloadRequest(
    val url: String,
    val referer: String,
    val userAgent: String?,
    /** "Cookie" header for the download request, see [WebEngine.getCookies] */
    val cookies: String? = null,
    /** Null to guess it from [url], [contentDisposition] and [mimeType] */
    val fileName: String? = null,
    val mimeType: String? = null,
    val contentDisposition: String? = null,
    val size: Long = 0L,
    /** Content already opened by the engine (a response it fetched, a blob: url the page reads) */
    val stream: InputStream? = null,
    /** The response of the engine tells the server gives byte ranges of the file: it can be resumed */
    val acceptsRanges: Boolean = false,
    /** The validators of the version of the file in the response of the engine, for resuming it */
    val etag: String? = null,
    val lastModified: String? = null,
    val operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP,
    /** A video stream to save as one file ([url] is its master playlist), null for a file */
    val streamSpec: StreamSpec? = null,
) {
    companion object {
        /**
         * Download of a link chosen by the user (for example in a context menu).
         */
        fun forLink(url: String, referer: String, userAgent: String?, cookies: String?) = DownloadRequest(
            url = url,
            referer = referer,
            userAgent = userAgent,
            cookies = cookies,
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
        )
    }
}
