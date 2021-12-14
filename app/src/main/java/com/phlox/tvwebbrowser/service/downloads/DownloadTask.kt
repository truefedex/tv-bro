package com.phlox.tvwebbrowser.service.downloads

import android.util.Base64
import android.webkit.CookieManager
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.DownloadUtils
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.HttpsURLConnection

/**
 * Created by PDT on 23.01.2017.
 */

const val MAX_CONNECT_RETRIES = 5

interface DownloadTask {
    var downloadInfo: Download

    interface Callback {
        fun onProgress(task: DownloadTask)
        fun onError(task: DownloadTask, responseCode: Int, responseMessage: String)
        fun onDone(task: DownloadTask)
    }
}

class FileDownloadTask(override var downloadInfo: Download, private val userAgent: String, val callback: DownloadTask.Callback) : Runnable, DownloadTask {

    override fun run() {
        downloadInfo.id = AppDatabase.db.downloadDao().insert(downloadInfo)

        var input: InputStream? = null
        var output: OutputStream? = null
        val url = URL(downloadInfo.url)

        var connection: HttpURLConnection? = null
        try {
            var retries = 0
            do {
                connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    readTimeout = 10000
                    connectTimeout = 20000
                    setRequestProperty("User-Agent", userAgent)
                    downloadInfo.mimeType?.apply { setRequestProperty("Content-Type", this) }
                    downloadInfo.referer?.apply { setRequestProperty("Referer", this) }
                    useCaches = false
                    val cookie = CookieManager.getInstance().getCookie(url.toString())
                    if (cookie != null) setRequestProperty("cookie", cookie)
                    if (retries > 0) {
                        //trust me, sometimes this helps! Don't ask me how...
                        Thread.sleep(3000)
                    }
                    connect()
                }

                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> break
                    HttpURLConnection.HTTP_GATEWAY_TIMEOUT,
                    HttpURLConnection.HTTP_UNAVAILABLE -> {
                        retries++
                        if (retries >= MAX_CONNECT_RETRIES) {
                            downloadInfo.size = Download.BROKEN_MARK
                            callback.onError(this, connection.responseCode, connection.responseMessage)
                            return
                        }
                    }
                    else -> {
                        downloadInfo.size = Download.BROKEN_MARK
                        callback.onError(this, connection.responseCode, connection.responseMessage)
                        return
                    }
                }
            } while (true)

            input = connection!!.inputStream

            val fileLength = connection.contentLength
            downloadInfo.size = fileLength.toLong()

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
                if (downloadInfo.cancelled) {
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
            if (downloadInfo.cancelled) {
                File(downloadInfo.filepath).delete()
            }
        }
        downloadInfo.size = downloadInfo.bytesReceived
        callback.onDone(this)
    }
}

class BlobDownloadTask(override var downloadInfo: Download, val blobBase64Data: String, val callback: DownloadTask.Callback) : Runnable, DownloadTask {

    override fun run() {
        downloadInfo.id = AppDatabase.db.downloadDao().insert(downloadInfo)

        try {
            val blobAsBytes: ByteArray = Base64.decode(blobBase64Data.replaceFirst("data:${downloadInfo.mimeType};base64,", ""), 0)
            File(downloadInfo.filepath).writeBytes(blobAsBytes)
            downloadInfo.size = blobAsBytes.size.toLong()
            downloadInfo.bytesReceived = downloadInfo.size
        } catch (e: Exception) {
            downloadInfo.size = Download.BROKEN_MARK
            callback.onError(this, 0, e.toString())
            return
        } finally {
            if (downloadInfo.cancelled) {
                File(downloadInfo.filepath).delete()
            }
        }
        callback.onDone(this)
    }
}
