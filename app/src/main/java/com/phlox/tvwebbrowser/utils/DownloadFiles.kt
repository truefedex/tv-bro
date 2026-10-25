package com.phlox.tvwebbrowser.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * The file of a download by what its `filepath` holds: a path in the public Downloads directory (before
 * Android 11), a content uri of MediaStore Downloads (Android 11+), or a document of the folder the user chose
 * ([DownloadFolder]). The kind is told by the path, not by the version of Android: a row keeps the kind it got.
 */
object DownloadFiles {
    private const val TAG = "DownloadFiles"

    enum class Kind { FILE, MEDIA_STORE, DOCUMENT }

    /** null for an empty path */
    fun kindOf(filepath: String): Kind? = when {
        filepath.isEmpty() -> null
        filepath.startsWith("/") -> Kind.FILE
        filepath.startsWith("content://${MediaStore.AUTHORITY}/") -> Kind.MEDIA_STORE
        else -> Kind.DOCUMENT
    }

    /** Whether the file is still there. Blocking */
    fun exists(context: Context, filepath: String): Boolean {
        val column = when (kindOf(filepath)) {
            null -> return false
            Kind.FILE -> return File(filepath).exists()
            Kind.MEDIA_STORE -> MediaStore.Downloads._ID
            Kind.DOCUMENT -> DocumentsContract.Document.COLUMN_DOCUMENT_ID
        }
        return try {
            context.contentResolver.query(Uri.parse(filepath), arrayOf(column), null, null, null)
                ?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** A uri other apps can read (with a grant), null when there is no file */
    fun uriOf(context: Context, filepath: String): Uri? = when (kindOf(filepath)) {
        null -> null
        Kind.FILE -> File(filepath).takeIf { it.exists() }
            ?.let { FileProvider.getUriForFile(context, context.packageName + ".provider", it) }
        Kind.MEDIA_STORE, Kind.DOCUMENT -> Uri.parse(filepath)
    }

    /** Deletes the file, if it is still there. Blocking */
    fun delete(context: Context, filepath: String) {
        try {
            when (kindOf(filepath)) {
                null -> {}
                Kind.FILE -> File(filepath).delete()
                Kind.MEDIA_STORE -> context.contentResolver.delete(Uri.parse(filepath), null, null)
                //documents providers do not implement ContentResolver.delete
                Kind.DOCUMENT -> DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(filepath))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can not delete $filepath", e)
        }
    }

    /**
     * Opens the file of a download that had started to write it again; a MediaStore item is made pending again
     * (other apps do not see it until [publish])
     */
    fun openForWriting(context: Context, filepath: String): ParcelFileDescriptor = when (kindOf(filepath)) {
        null -> throw IOException("No file")
        Kind.FILE -> ParcelFileDescriptor.open(File(filepath),
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
        Kind.MEDIA_STORE -> {
            val uri = Uri.parse(filepath)
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) },
                null, null)
            context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Can not open the file")
        }
        Kind.DOCUMENT -> context.contentResolver.openFileDescriptor(Uri.parse(filepath), "rw")
            ?: throw IOException("Can not open the file")
    }

    /** Makes the complete file visible to other apps: only MediaStore items are hidden while they are pending */
    fun publish(context: Context, filepath: String) {
        if (kindOf(filepath) != Kind.MEDIA_STORE) return
        try {
            context.contentResolver.update(Uri.parse(filepath),
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Can not publish $filepath", e)
        }
    }
}
