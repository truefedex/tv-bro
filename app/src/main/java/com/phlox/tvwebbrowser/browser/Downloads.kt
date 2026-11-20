package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.DownloadUtils
import com.phlox.tvwebbrowser.webengine.DownloadRequest

/**
 * The download to start for the request.
 */
fun DownloadRequest.toDownload(): Download {
    val fileName = fileName ?: DownloadUtils.guessFileName(url, contentDisposition, mimeType)
    return Download(url, fileName, null, operationAfterDownload,
        mimeType, referer, userAgent, stream, size).also {
        it.cookies = cookies
        //a response the engine fetched itself can be continued by the download service
        it.resumable = acceptsRanges && size > 0 &&
            (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))
        it.etag = etag
        it.lastModified = lastModified
        streamSpec?.let { spec ->
            it.streamSpec = spec.toJson()
            //its segments are kept while it is paused
            it.resumable = true
        }
    }
}
