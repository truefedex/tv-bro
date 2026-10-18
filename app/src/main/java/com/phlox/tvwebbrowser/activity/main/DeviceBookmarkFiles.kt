package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.launchForResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * The bookmarks files of [BookmarksNavigator] on the device. They are saved in the public Downloads
 * directory as downloads are: through MediaStore from Android 11, else as files after the storage
 * permission is granted; a saved file is listed in the downloads of the app.
 */
class DeviceBookmarkFiles(private val activity: ComponentActivity) : BookmarksNavigator.Files {
    companion object {
        private const val TAG = "DeviceBookmarkFiles"
        private const val MIME_TYPE = "text/html"
    }

    override suspend fun saveToDownloads(name: String, html: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return null
        }
        val bytes = html.toByteArray()
        return withContext(Dispatchers.IO) {
            try {
                val (savedName, path) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    saveToMediaStore(name, bytes)
                } else {
                    saveToDirectory(name, bytes)
                } ?: return@withContext null
                TVBro.container.data.downloads.insert(Download().apply {
                    filename = savedName
                    filepath = path
                    time = System.currentTimeMillis()
                    size = bytes.size.toLong()
                    bytesReceived = size
                    mimeType = MIME_TYPE
                })
                savedName
            } catch (e: Exception) {
                Log.e(TAG, "Can not save $name", e)
                null
            }
        }
    }

    /** @return the name the file got (MediaStore renames it if the name is taken) and its uri */
    private fun saveToMediaStore(name: String, bytes: ByteArray): Pair<String, String>? {
        val resolver = activity.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("No output for $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val savedName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return savedName to uri.toString()
    }

    /** @return the name the file got (with a number if the name is taken, as downloads do) and its path */
    @Suppress("DEPRECATION")
    private fun saveToDirectory(name: String, bytes: ByteArray): Pair<String, String>? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return null
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        var file = File(dir, name)
        var i = 0
        while (file.exists()) {
            i++
            file = File(dir, if (extension.isEmpty()) "${base}_($i)" else "${base}_($i).$extension")
        }
        file.writeBytes(bytes)
        return file.name to file.absolutePath
    }

    /** Asks for the [permission] if it is not granted yet */
    private suspend fun granted(permission: String): Boolean {
        if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        return suspendCancellableCoroutine { continuation ->
            activity.launchForResult(ActivityResultContracts.RequestPermission(), permission) { granted ->
                continuation.resume(granted)
            }
        }
    }
}
