package com.phlox.tvwebbrowser.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.phlox.tvwebbrowser.model.Download
import java.io.File

/**
 * The files of downloads: their path is a content uri of MediaStore on Android 11+ and a path in
 * the public Downloads directory before.
 */
object DownloadFileActions {
    private const val TAG = "DownloadFileActions"
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    /** Android TV: the activities that tell there is no app for an intent */
    private const val TV_STUBS_PACKAGE = "com.android.tv.frameworkpackagestubs"

    /** A uri other apps can read, null when the file is gone */
    fun uriOf(context: Context, download: Download): Uri? {
        if (download.filepath.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Uri.parse(download.filepath)
        }
        val file = File(download.filepath)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(context, context.packageName + ".provider", file)
    }

    fun isApk(download: Download): Boolean =
        download.filename.endsWith(".apk", ignoreCase = true) || download.mimeType == APK_MIME_TYPE

    /** @return false if the file is gone or no app opens it */
    fun open(context: Context, download: Download): Boolean {
        val uri = uriOf(context, download) ?: return false
        val mimeType = context.contentResolver.getType(uri) ?: download.mimeType
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent)
    }

    /** Opens the package installer, @return false if the file is gone or there is no installer */
    fun install(context: Context, download: Download): Boolean {
        val uri = uriOf(context, download) ?: return false
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent)
    }

    /** Deletes the file of the download, if it is still there. Blocking */
    fun deleteFile(context: Context, download: Download) {
        if (download.filepath.isEmpty()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.contentResolver.delete(Uri.parse(download.filepath), null)
            } else {
                File(download.filepath).delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can not delete ${download.filepath}", e)
        }
    }

    /** Some app other than the stubs of Android TV takes the [intent] */
    fun hasApp(context: Context, intent: Intent): Boolean =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.packageName != TV_STUBS_PACKAGE }

    /**
     * Starts the activity of the [intent], @return false if there is none. Android TV sends intents no
     * app handles to its stubs, which only show a toast: they count as none.
     */
    fun start(context: Context, intent: Intent): Boolean {
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved?.activityInfo?.packageName == TV_STUBS_PACKAGE) return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Can not start $intent", e)
            false
        }
    }
}
