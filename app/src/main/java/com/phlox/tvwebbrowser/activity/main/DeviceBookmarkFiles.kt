package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.data.BookmarksHtml
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
 * permission is granted; a saved file is listed in the downloads of the app. The files in Downloads are
 * listed the same way: from Android 11 MediaStore shows only the files of the app itself (its exports and
 * downloads), the others are opened with the system picker.
 */
class DeviceBookmarkFiles(private val activity: ComponentActivity) : BookmarksNavigator.Files {
    companion object {
        private const val TAG = "DeviceBookmarkFiles"
        private const val MIME_TYPE = "text/html"
        private const val TV_STUBS_PACKAGE = "com.android.tv.frameworkpackagestubs"
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

    override suspend fun listDownloads(): List<BookmarksNavigator.FileInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) listMediaStore() else listDirectory()
            } catch (e: Exception) {
                Log.e(TAG, "Can not list the downloads", e)
                emptyList()
            }
        }
    }

    private fun isHtml(name: String) = name.endsWith(".html", ignoreCase = true) || name.endsWith(".htm", ignoreCase = true)

    private fun listMediaStore(): List<BookmarksNavigator.FileInfo> {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_MODIFIED, MediaStore.Downloads.SIZE)
        val files = ArrayList<BookmarksNavigator.FileInfo>()
        activity.contentResolver.query(collection, columns, null, null,
            "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (!isHtml(name)) continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                files += BookmarksNavigator.FileInfo(name, uri.toString(), cursor.getLong(2) * 1000,
                    if (cursor.isNull(3)) -1 else cursor.getLong(3))
            }
        }
        return files
    }

    @Suppress("DEPRECATION")
    private fun listDirectory(): List<BookmarksNavigator.FileInfo> {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return (dir.listFiles() ?: return emptyList())
            .filter { it.isFile && isHtml(it.name) && it.canRead() }
            .sortedByDescending { it.lastModified() }
            .map { BookmarksNavigator.FileInfo(it.name, Uri.fromFile(it).toString(), it.lastModified(), it.length()) }
    }

    override suspend fun pick(): BookmarksNavigator.FileInfo? {
        val contract = ActivityResultContracts.OpenDocument()
        val input = arrayOf(MIME_TYPE)
        //Android TV answers with a stub that only says there is no app for it
        val handler = activity.packageManager.resolveActivity(contract.createIntent(activity, input), 0)
        if (handler?.activityInfo?.packageName == TV_STUBS_PACKAGE) throw ActivityNotFoundException()
        val uri = suspendCancellableCoroutine<Uri?> { continuation ->
            activity.launchForResult(contract, input) { uri ->
                continuation.resume(uri)
            }
        } ?: return null
        return withContext(Dispatchers.IO) {
            var name: String? = null
            var size = -1L
            try {
                activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0)
                        if (!cursor.isNull(1)) size = cursor.getLong(1)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No name of $uri", e)
            }
            BookmarksNavigator.FileInfo(name ?: uri.lastPathSegment ?: uri.toString(), uri.toString(), 0, size)
        }
    }

    /** Read and parsed in the background: files of browsers may hold megabytes of icons */
    override suspend fun read(file: BookmarksNavigator.FileInfo): List<BookmarksHtml.Node>? = withContext(Dispatchers.IO) {
        try {
            activity.contentResolver.openInputStream(Uri.parse(file.uri))?.use {
                BookmarksHtml.parse(it.readBytes().toString(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Can not read ${file.uri}", e)
            null
        }
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
