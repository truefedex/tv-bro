package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.net.AppProxy
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import com.phlox.tvwebbrowser.browser.toDownload
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.StreamSpec
import com.phlox.tvwebbrowser.service.downloads.DownloadCommands
import com.phlox.tvwebbrowser.service.downloads.DownloadServiceNeed
import com.phlox.tvwebbrowser.utils.DownloadFolder
import com.phlox.tvwebbrowser.utils.launchForResult
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream

/**
 * Starts and controls downloads in [DownloadService] on behalf of the
 * [activity]: asks for the needed permissions, inserts the database row of a new download and
 * sends the commands. The service is bound while the activity is started and needs it ([DownloadServiceNeed]);
 * commands sent meanwhile bind it until they are delivered. New files go to the folder the user chose
 * ([DownloadFolder]) while the app may write there, else to the Downloads directory ([onFolderUnavailable] tells it).
 */
class DownloadLauncher(
    private val activity: ComponentActivity,
    private val onFolderUnavailable: () -> Unit,
    private val onDownloadStarted: (Download) -> Unit
) {
    companion object {
        private const val TAG = "DownloadLauncher"
    }

    private val service get() = TVBro.container.downloadService

    /** The service is bound while it is needed, not all the time the activity is started */
    private val need = DownloadServiceNeed { needed -> if (needed) service.acquire() else service.release() }

    fun onActivityStarted() {
        need.started = true
    }

    fun onActivityStopped() {
        need.started = false
    }

    /** Whether a download of the database is in progress */
    fun onDownloadsInProgress(inProgress: Boolean) {
        need.inProgress = inProgress
    }

    /** A downloads screen or panel was shown, or closed: its commands go to the service at once */
    fun onScreenShown(shown: Boolean) {
        need.onScreenShown(shown)
    }

    fun download(request: DownloadRequest) {
        //not of a site the site filter blocks (the kids profile; the page may be one a parent allowed)
        val filter = WebEngineFactory.siteFilter
        if (filter != null && filter.active && filter.check(Uri.parse(request.url), false,
                request.referer.takeIf { it.isNotEmpty() }?.let(Uri::parse)) != null) {
            Utils.showToast(activity, activity.getString(com.phlox.tvwebbrowser.common.R.string.site_blocked_title))
            return
        }
        val download = request.toDownload()
        val (folder, unavailable) = chooseFolder()
        //the toast of the started download would replace the warning at once: the warning shows in its place
        val begin = { start(download, folder, folderUnavailable = unavailable) }
        val neededPermission = missingPermission(folder)
        if (neededPermission == null) {
            begin()
            return
        }
        activity.launchForResult(ActivityResultContracts.RequestPermission(), neededPermission) { granted ->
            if (granted) {
                begin()
            } else if (unavailable) {
                onFolderUnavailable()
            }
        }
    }

    /** A download whose bytes the caller writes into [output] (a file a phone sends) */
    class Incoming(val download: Download, val output: ParcelFileDescriptor)

    /**
     * Starts a download of the bytes the caller writes (a file a phone sends): it is not fetched, not
     * resumable and not told by [onDownloadStarted].
     * The notifications are asked for, but not waited for: the file goes on without them.
     * @return the download and the write end of its pipe once the service has the command (close it when all is
     *   written, `closeWithError` if not), or null if the storage permission is missing: it is asked for, and
     *   nothing waits for it
     */
    suspend fun receive(request: DownloadRequest): Incoming? {
        val download = request.toDownload()
        download.resumable = false
        val (folder, unavailable) = chooseFolder()
        //no toast tells this download started
        if (unavailable) onFolderUnavailable()
        val missing = missingPermission(folder)
        if (missing != null) activity.launchForResult(ActivityResultContracts.RequestPermission(), missing) {}
        if (missing == Manifest.permission.WRITE_EXTERNAL_STORAGE) return null
        val (readEnd, writeEnd) = ParcelFileDescriptor.createReliablePipe()
        val sent = CompletableDeferred<Boolean>()
        start(download, folder, readEnd, notify = false) { sent.complete(it) }
        if (!sent.await()) {
            readEnd.close()
            writeEnd.close()
            throw IOException("The download can not be started")
        }
        return Incoming(download, writeEnd)
    }

    /**
     * The folder the user chose while the app may write there, else null (Downloads), and whether the user
     * chose a folder that can not be used now
     */
    private fun chooseFolder(): Pair<Uri?, Boolean> {
        val config = AppContext.provideConfig()
        val chosen = DownloadFolder.chosen(config)
        val folder = chosen?.let { tree ->
            when {
                //the grant does not come back (revoked, a backup of another device): the setting goes
                !DownloadFolder.granted(activity, tree) -> {
                    DownloadFolder.reset(activity, config)
                    null
                }
                //deleted, or its drive is not there now
                !DownloadFolder.present(activity, tree) -> null
                else -> tree
            }
        }
        return folder to (chosen != null && folder == null)
    }

    /** A permission a download into the [folder] needs and the app does not have */
    private fun missingPermission(folder: Uri?): String? {
        return when {
            //the folder of the Storage Access Framework needs no permission, the Downloads directory does
            folder == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                Manifest.permission.POST_NOTIFICATIONS
            else -> null
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
            AppProxy.current?.let { data.putBundle(DownloadCommands.KEY_PROXY, it.toBundle()) }
            //a new file, if the old one is gone
            DownloadFolder.usable(activity, AppContext.provideConfig())
                ?.let { data.putString(DownloadCommands.KEY_FOLDER, it.toString()) }
        })
    }

    /** Downloads the url of the [item] again, as a new download */
    fun downloadAgain(item: DownloadItem, userAgent: String?, cookies: String?) {
        //a stream is saved again from its playlist
        download(DownloadRequest(url = item.url, referer = item.referer ?: "", userAgent = userAgent,
            cookies = cookies, fileName = item.filename, mimeType = item.mimeType,
            streamSpec = StreamSpec.fromJson(item.streamSpec)))
    }

    /**
     * @param stream the read end of a pipe the caller fills, else the bytes the engine has ([engineInput]) or none
     * @param onSent whether the command went to the service
     */
    private fun start(download: Download, folder: Uri?, stream: ParcelFileDescriptor? = null, notify: Boolean = true,
                      folderUnavailable: Boolean = false, onSent: (Boolean) -> Unit = {}) {
        //a page, not about:blank or the like
        val referer = download.referer?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        //incognito downloads do not keep the page they came from
        download.referer = if (AppContext.provideConfig().incognitoMode) null else referer
        download.status = Download.Status.IN_PROGRESS
        //of the profile shown now: the kids profile lists only its own
        download.kids = AppContext.kidsProfile
        //the row is inserted when the service has checked the rows a dead process left, and it outlives
        //the activity: it is the download now
        service.acquire()
        service.whenConnected {
            TVBro.container.appScope.launch {
                var sent = false
                try {
                    //later than the start of the service: its check of the rows left does not take it
                    download.time = System.currentTimeMillis()
                    download.id = TVBro.container.mainData.downloads.insert(download)
                    val message = command(DownloadCommands.START, download.id).apply {
                        data.putString(DownloadCommands.KEY_COOKIES, download.cookies)
                        data.putString(DownloadCommands.KEY_REFERER, referer)
                        AppProxy.current?.let { data.putBundle(DownloadCommands.KEY_PROXY, it.toBundle()) }
                        folder?.let { data.putString(DownloadCommands.KEY_FOLDER, it.toString()) }
                        data.putBoolean(DownloadCommands.KEY_INSTALL,
                            download.operationAfterDownload == Download.OperationAfterDownload.INSTALL)
                        (stream ?: engineInput(download)?.let { pipe(it) })
                            ?.let { data.putParcelable(DownloadCommands.KEY_STREAM, it) }
                    }
                    service.send(message)
                    sent = true
                    if (folderUnavailable) onFolderUnavailable() else if (notify) onDownloadStarted(download)
                } finally {
                    service.release()
                    onSent(sent)
                }
            }
        }
    }

    /** The contents of a download the web engine or the page has, they can not be fetched again */
    private fun engineInput(download: Download): InputStream? = download.stream

    /**
     * The read end of a pipe the [input] is copied to, for the service. If the input fails, the service gets an
     * error at the end of the pipe.
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
