package com.phlox.tvwebbrowser.webengine

import android.webkit.MimeTypeMap
import com.phlox.tvwebbrowser.model.Download
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
    /** Content of a blob: url, already read by the page scripts */
    val base64BlobData: String? = null,
    /** Content already opened by the engine */
    val stream: InputStream? = null,
    val operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP
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
