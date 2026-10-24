package com.phlox.tvwebbrowser.service.downloads

import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.net.AppProxy
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.utils.DownloadUtils
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** What the response to a request of the bytes of a file from an offset means for the part already there */
enum class RangeDecision {
    /** The response has the rest of the file: append it */
    APPEND,
    /** The response, or the next one without a range, has the whole file: write it again */
    RESTART,
    /** The part already there is the whole file */
    COMPLETE,
    FAIL
}

/**
 * @param code of the response to a request of the bytes from [offset] (with If-Range when the file had validators)
 * @param rangeStart the first byte of the Content-Range of the response, if any
 * @param size of the whole file, 0 if it is not known
 */
fun decideRange(code: Int, offset: Long, rangeStart: Long?, size: Long): RangeDecision = when (code) {
    //another start than asked: the file is taken again from its start
    HttpURLConnection.HTTP_PARTIAL -> if (rangeStart == offset) RangeDecision.APPEND else RangeDecision.RESTART
    //the server ignores ranges, or the file changed (If-Range)
    HttpURLConnection.HTTP_OK -> RangeDecision.RESTART
    416 -> if (size > 0 && offset >= size) RangeDecision.COMPLETE else RangeDecision.RESTART
    else -> RangeDecision.FAIL
}

/** "bytes 100-199/200": 100; null if it is not such a header */
fun contentRangeStart(header: String?): Long? =
    header?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()

/** "bytes 100-199/200": 200; null if the size is unknown (*) or it is not such a header */
fun contentRangeSize(header: String?): Long? = header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

/** The body of a response, the file from [start] */
class HttpResponse(val connection: HttpURLConnection?, val input: InputStream, val start: Long)

/**
 * Requests the files of downloads: follows redirects itself (also between http and https, and the
 * cookies of the site do not go to other hosts), and asks for the rest of a file after a pause.
 */
object HttpDownloads {
    private const val MAX_REDIRECTS = 10
    const val MAX_CONNECT_RETRIES = 5
    private const val RETRY_DELAY = 3000L

    /**
     * Opens the url of the [download] for its bytes from [offset] (0 for the whole file), and fills
     * what the response tells about the file: its size, the validators of its version, whether the
     * server gives ranges of it, and (for the whole file) its name and type. All its requests (redirects
     * and retries too) go through [proxy], the proxy of the browser when the download started.
     */
    fun open(download: Download, offset: Long, retryDelay: Long = RETRY_DELAY, proxy: ProxySettings? = null): HttpResponse {
        if (offset > 0) {
            val connection = connect(download, offset, retryDelay, proxy)
            val code = connection.responseCode
            when (decideRange(code, offset, contentRangeStart(connection.getHeaderField("Content-Range")), download.size)) {
                RangeDecision.APPEND -> {
                    readFile(download, connection, whole = false)
                    return HttpResponse(connection, connection.inputStream, offset)
                }
                RangeDecision.COMPLETE -> {
                    connection.disconnect()
                    return HttpResponse(null, ByteArrayInputStream(ByteArray(0)), offset)
                }
                RangeDecision.RESTART -> if (code == HttpURLConnection.HTTP_OK) {
                    readFile(download, connection, whole = false)
                    return HttpResponse(connection, connection.inputStream, 0)
                } else {
                    connection.disconnect()
                }
                RangeDecision.FAIL -> {
                    connection.disconnect()
                    throw HttpException(code)
                }
            }
        }
        val connection = connect(download, 0, retryDelay, proxy)
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw HttpException(code)
        }
        readFile(download, connection, whole = offset == 0L)
        return HttpResponse(connection, connection.inputStream, 0)
    }

    private fun connect(download: Download, offset: Long, retryDelay: Long, proxy: ProxySettings?): HttpURLConnection {
        var url = URL(download.url)
        val siteHost = url.host
        var redirects = 0
        var retries = 0
        while (true) {
            val connection = (AppProxy.open(url, proxy) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                readTimeout = 10000
                connectTimeout = 20000
                useCaches = false
                //the bytes of the file as they are: ranges of a compressed body would not fit together
                setRequestProperty("Accept-Encoding", "identity")
                download.userAgentString?.let { setRequestProperty("User-Agent", it) }
                download.mimeType?.takeIf { it != "application/octet-stream" }?.let { setRequestProperty("Accept", it) }
                download.refererHeader?.takeIf { it.isNotEmpty() }?.let { setRequestProperty("Referer", it) }
                if (url.host == siteHost) download.cookies?.let { setRequestProperty("Cookie", it) }
                if (offset > 0) {
                    setRequestProperty("Range", "bytes=$offset-")
                    (download.etag ?: download.lastModified)?.let { setRequestProperty("If-Range", it) }
                }
            }
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            when {
                code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED && location != null -> {
                    connection.disconnect()
                    if (++redirects > MAX_REDIRECTS) throw HttpException(code)
                    url = URL(url, location)
                }
                code == HttpURLConnection.HTTP_UNAVAILABLE || code == HttpURLConnection.HTTP_GATEWAY_TIMEOUT -> {
                    connection.disconnect()
                    if (++retries >= MAX_CONNECT_RETRIES) throw HttpException(code)
                    //trust me, sometimes this helps! Don't ask me how...
                    Thread.sleep(retryDelay)
                }
                else -> return connection
            }
        }
    }

    /** What the response tells about the file; [whole]: a new download, it gets the name of the response */
    private fun readFile(download: Download, connection: HttpURLConnection, whole: Boolean) {
        val partial = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        val size = if (partial) contentRangeSize(connection.getHeaderField("Content-Range")) ?: 0
            else connection.contentLengthLong
        download.size = size.coerceAtLeast(0)
        download.etag = connection.getHeaderField("ETag")
        download.lastModified = connection.getHeaderField("Last-Modified")
        download.resumable = download.size > 0 &&
            (partial || connection.getHeaderField("Accept-Ranges")?.trim().equals("bytes", ignoreCase = true))
        val contentType = connection.getHeaderField("Content-Type")
        if (whole) {
            connection.getHeaderField("Content-Disposition")?.let {
                download.filename = DownloadUtils.guessFileName(download.url, it, contentType)
            }
        }
        if (download.mimeType == null && contentType != null) {
            download.mimeType = contentType.substringBefore(';').trim()
        }
    }
}

class HttpException(val code: Int) : java.io.IOException("HTTP $code")
