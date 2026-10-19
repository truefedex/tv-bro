package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.browser.decodeBase64Data
import com.phlox.tvwebbrowser.browser.toDownload
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.service.downloads.DownloadCommands
import com.phlox.tvwebbrowser.utils.launchForResult
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Starts and controls downloads in [DownloadService] (its own process) on behalf of the
 * [activity]: asks for the needed permissions, inserts the database row of a new download and
 * sends the commands. The service is bound while the activity is started.
 */
class DownloadLauncher(
    private val activity: ComponentActivity,
    private val onDownloadStarted: (Download) -> Unit
) {
    companion object {
        private const val TAG = "DownloadLauncher"
    }

    private val service get() = TVBro.container.downloadService

    /** Keeps the service bound while the activity is started */
    fun bindService() {
        service.acquire()
    }

    fun unbindService() {
        service.release()
    }

    fun download(request: DownloadRequest) {
        val download = request.toDownload()
        val neededPermission = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                Manifest.permission.POST_NOTIFICATIONS
            else -> null
        }
        if (neededPermission == null) {
            start(download)
            return
        }
        activity.launchForResult(ActivityResultContracts.RequestPermission(), neededPermission) { granted ->
            if (granted) {
                start(download)
            }
        }
    }

    fun cancel(id: Long) {
        service.send(command(DownloadCommands.CANCEL, id))
    }

    fun pause(id: Long) {
        service.send(command(DownloadCommands.PAUSE, id))
    }

    /** Continues the paused download [item] with the [cookies] the browser has now for its site */
    fun resume(item: DownloadItem, cookies: String?) {
        service.send(command(DownloadCommands.RESUME, item.id).apply {
            data.putString(DownloadCommands.KEY_COOKIES, cookies)
            data.putString(DownloadCommands.KEY_REFERER, item.referer)
        })
    }

    /** Downloads the url of the [item] again, as a new download */
    fun downloadAgain(item: DownloadItem, userAgent: String?, cookies: String?) {
        download(DownloadRequest(url = item.url, referer = item.referer ?: "", userAgent = userAgent,
            cookies = cookies, fileName = item.filename, mimeType = item.mimeType))
    }

    private fun start(download: Download) {
        //a page, not about:blank or the like
        val referer = download.referer?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        //incognito downloads do not keep the page they came from
        download.referer = if (AppContext.provideConfig().incognitoMode) null else referer
        download.status = Download.Status.IN_PROGRESS
        //the row is inserted when the service has checked the rows a dead process left, and it outlives
        //the activity: it is the download now
        service.acquire()
        service.whenConnected {
            TVBro.container.appScope.launch {
                try {
                    //later than the start of the service: its check of the rows left does not take it
                    download.time = System.currentTimeMillis()
                    download.id = TVBro.container.data.downloads.insert(download)
                    val message = command(DownloadCommands.START, download.id).apply {
                        data.putString(DownloadCommands.KEY_COOKIES, download.cookies)
                        data.putString(DownloadCommands.KEY_REFERER, referer)
                        data.putBoolean(DownloadCommands.KEY_INSTALL,
                            download.operationAfterDownload == Download.OperationAfterDownload.INSTALL)
                        engineInput(download)?.let { data.putParcelable(DownloadCommands.KEY_STREAM, pipe(it)) }
                    }
                    service.send(message)
                    onDownloadStarted(download)
                } finally {
                    service.release()
                }
            }
        }
    }

    /** The contents of a download the web engine fetched itself, they can not be fetched again */
    private fun engineInput(download: Download): InputStream? {
        download.stream?.let { return it }
        val blobData = download.base64BlobData ?: return null
        return try {
            ByteArrayInputStream(decodeBase64Data(blobData))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Wrong blob data of ${download.url}", e)
            ByteArrayInputStream(ByteArray(0))
        }
    }

    /**
     * The read end of a pipe the [input] is copied to, for the service process. If this process
     * dies or the input fails, the service gets an error at the end of the pipe.
     */
    private fun pipe(input: InputStream): ParcelFileDescriptor {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createReliablePipe()
        TVBro.container.appScope.launch(Dispatchers.IO) {
            try {
                input.use { ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output -> input.copyTo(output, 64 * 1024) } }
            } catch (e: IOException) {
                Log.w(TAG, "Can not pass the download to the service", e)
                try {
                    writeEnd.closeWithError(e.message ?: "IOException")
                } catch (ignored: IOException) {
                }
            }
        }
        return readEnd
    }

    private fun command(what: Int, id: Long) = Message.obtain(null, what).apply {
        data = Bundle().apply { putLong(DownloadCommands.KEY_ID, id) }
    }
}
