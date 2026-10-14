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
        mimeType, referer, userAgent, base64BlobData, stream, size).also {
        it.cookies = cookies
    }
}
