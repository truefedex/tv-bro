package com.phlox.tvwebbrowser.activity.main

import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.utils.DownloadFileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The files of downloads on the device, for [DownloadsNavigator] */
class DeviceDownloads(private val activity: Activity) : DownloadsNavigator.Device {
    companion object {
        private const val TAG = "DeviceDownloads"
        private const val DOWNLOADS_DOCUMENTS = "com.android.providers.downloads.documents"
    }

    @Suppress("DEPRECATION")
    private val downloadsDirectory get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    override fun fileExists(item: DownloadItem): Boolean {
        if (item.filepath.isEmpty()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return File(item.filepath).exists()
        return try {
            activity.contentResolver.query(Uri.parse(item.filepath), arrayOf(), null, null, null)?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun open(item: DownloadItem): Boolean = fileExists(item) && DownloadFileActions.open(activity, item.toDownload())

    override fun install(item: DownloadItem): Boolean = fileExists(item) && DownloadFileActions.install(activity, item.toDownload())

    override fun openFolder(): Boolean {
        val intents = listOf(
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
            //the Downloads root of the system files app
            Intent(Intent.ACTION_VIEW).setDataAndType(DocumentsContract.buildRootUri(DOWNLOADS_DOCUMENTS, "downloads"),
                DocumentsContract.Root.MIME_TYPE_ITEM),
            //file managers that take a directory
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(downloadsDirectory), "resource/folder")
        )
        return intents.any {
            try {
                DownloadFileActions.start(activity, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                //a file uri may be refused (FileUriExposedException)
                Log.w(TAG, "Can not open the folder with $it", e)
                false
            }
        }
    }

    override fun share(item: DownloadItem): Boolean {
        if (!fileExists(item)) return false
        val uri = DownloadFileActions.uriOf(activity, item.toDownload()) ?: return false
        val intent = Intent(Intent.ACTION_SEND)
            .setType(item.mimeType ?: activity.contentResolver.getType(uri) ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        //the chooser itself always starts: see first whether some app takes the file
        if (activity.packageManager.queryIntentActivities(intent, 0).isEmpty()) return false
        return DownloadFileActions.start(activity, Intent.createChooser(intent, activity.getString(R.string.share)))
    }

    override suspend fun deleteFile(item: DownloadItem) = withContext(Dispatchers.IO) {
        DownloadFileActions.deleteFile(activity, item.toDownload())
    }

    override fun copyLink(url: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(url, url))
    }

    override fun freeSpace(): Long = try {
        val directory = downloadsDirectory.takeIf { it.exists() } ?: Environment.getExternalStorageDirectory()
        StatFs(directory.path).availableBytes
    } catch (e: Exception) {
        0
    }
}
