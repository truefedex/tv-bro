package com.phlox.tvwebbrowser.service.downloads

import android.webkit.CookieManager
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.DownloadUtils
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Created by PDT on 23.01.2017.
 */

class DownloadTask(var downloadInfo: Download, private val userAgent: String, var callback: Callback) : Runnable {

    var isCancelled: Boolean
        get() = downloadInfo.cancelled
        set(cancelled) {
            downloadInfo.cancelled = cancelled
        }

    interface Callback {
        fun onProgress(task: DownloadTask)
        fun onError(task: DownloadTask, responseCode: Int, responseMessage: String)
        fun onDone(task: DownloadTask)
    }

    override fun run() {
        downloadInfo.id = AppDatabase.db.downloadDao().insert(downloadInfo)

        var input: InputStream? = null
        var output: OutputStream? = null
        var connection: HttpURLConnection? = null
        try {
            val url = URL(downloadInfo.url)
            connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", userAgent)
            downloadInfo.mimeType?.apply { connection.setRequestProperty("Content-Type", this)}
            downloadInfo.referer?.apply { connection.setRequestProperty("Referer", this)}
            connection.setRequestProperty("Expect", "100-continue")
            connection.useCaches = false
            val cookie = CookieManager.getInstance().getCookie(url.toString())
            if (cookie != null) connection.setRequestProperty("cookie", cookie)
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                downloadInfo.size = Download.BROKEN_MARK
                callback.onError(this, connection.responseCode, connection.responseMessage)
                return
            }

            val fileLength = connection.contentLength
            downloadInfo.size = fileLength.toLong()

            input = connection.inputStream
            if (connection.headerFields.containsKey("Content-Disposition")) {
                val mime = connection.getHeaderField("Content-Type")
                downloadInfo.filename = DownloadUtils.guessFileName(downloadInfo.url, connection.getHeaderField("Content-Disposition"), mime)
                downloadInfo.filepath = File(File(downloadInfo.filepath).parentFile, downloadInfo.filename).absolutePath
            }
            output = FileOutputStream(downloadInfo.filepath)

            val data = ByteArray(4096)
            var total: Long = 0
            var count = input.read(data)
            while (count != -1) {
                if (isCancelled) {
                    downloadInfo.bytesReceived = 0
                    downloadInfo.size = Download.CANCELLED_MARK
                    callback.onDone(this)
                    return
                }
                total += count.toLong()
                output.write(data, 0, count)
                downloadInfo.bytesReceived = total
                callback.onProgress(this)
                count = input.read(data)
            }
        } catch (e: Exception) {
            downloadInfo.size = Download.BROKEN_MARK
            callback.onError(this, 0, e.toString())
            return
        } finally {
            try {
                output?.close()
                input?.close()
            } catch (ignored: IOException) {
            }

            connection?.disconnect()
            if (isCancelled) {
                File(downloadInfo.filepath).delete()
            }
        }
        downloadInfo.size = downloadInfo.bytesReceived
        callback.onDone(this)
    }
}
