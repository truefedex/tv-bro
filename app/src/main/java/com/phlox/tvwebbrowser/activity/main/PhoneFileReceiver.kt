package com.phlox.tvwebbrowser.activity.main

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.MimeTypeMap
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.data.DownloadsRepository
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.PairedDevice
import com.phlox.tvwebbrowser.sync.SyncException
import com.phlox.tvwebbrowser.sync.SyncRequests
import com.phlox.tvwebbrowser.sync.SyncServer
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * Saves the files paired phones send ([SyncRequests.SEND_FILE]) as downloads of the TV: they go where
 * downloads go (the folder the user chose, or Downloads), show in the downloads screens with their progress
 * and can be cancelled there. The TV tells when a file comes and when it is saved ([notify], on the main thread).
 */
class PhoneFileReceiver(
    private val context: Context,
    private val host: BrowserShell.Host,
    private val downloads: () -> DownloadsRepository,
    private val notify: (message: String) -> Unit
) {
    companion object {
        private const val TAG = "PhoneFileReceiver"
    }

    /** Called on the main thread, see [SyncServer.Host.receiveFile] */
    suspend fun receive(from: PairedDevice, name: String, mimeType: String?, size: Long): SyncServer.IncomingFile {
        //the type of the extension: the phone's only when the extension tells nothing
        val type = mimeTypeOf(name) ?: mimeType
        val request = DownloadRequest(url = SyncRequests.phoneFileUrl(from.name), referer = "", userAgent = null,
            fileName = name, mimeType = type, size = size.coerceAtLeast(0L))
        val incoming = try {
            host.receiveDownload(request)
        } catch (e: IOException) {
            Log.e(TAG, "can not start $name", e)
            throw SyncException(SyncRequests.STORAGE)
        } ?: throw SyncException(SyncRequests.NEED_PERMISSION)
        notify(context.getString(R.string.sync_file_receiving, incoming.download.filename, from.name))
        return File(incoming, from)
    }

    private fun mimeTypeOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private inner class File(private val incoming: DownloadLauncher.Incoming, private val from: PairedDevice) :
        SyncServer.IncomingFile {
        override val output: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(incoming.output)

        override fun fail(reason: String) {
            try {
                //the download service drops what it wrote
                incoming.output.closeWithError(reason)
            } catch (e: Exception) {
                //closed already
            }
        }

        override suspend fun awaitSaved(): String {
            val id = incoming.download.id
            //the service writes the end of the download to the database
            val row = downloads().observeAll()
                .map { rows -> rows.firstOrNull { it.id == id } }
                .first { it == null || it.status != Download.Status.IN_PROGRESS }
            val name = row?.filename ?: incoming.download.filename
            when (row?.status) {
                Download.Status.DONE -> {
                    withContext(Dispatchers.Main) { notify(context.getString(R.string.sync_file_received, name, from.name)) }
                    return name
                }
                //removed from the list, or cancelled
                null, Download.Status.CANCELLED -> throw SyncException(SyncRequests.CANCELLED)
                else -> {
                    withContext(Dispatchers.Main) { notify(context.getString(R.string.sync_file_not_saved, name)) }
                    throw SyncException(if (row.error == Download.Error.STORAGE) SyncRequests.STORAGE else SyncRequests.FAILED)
                }
            }
        }
    }
}
