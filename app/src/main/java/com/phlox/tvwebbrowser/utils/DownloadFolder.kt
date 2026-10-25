package com.phlox.tvwebbrowser.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import com.phlox.tvwebbrowser.Config
import java.io.File
import java.io.IOException

/**
 * The folder the user chose for downloads with the picker of the Storage Access Framework
 * ([Intent.ACTION_OPEN_DOCUMENT_TREE]): a tree uri kept in [Config.downloadFolderUri] with a persisted grant to
 * read and write it. The grant is the app's, so the downloads process can write there too. Without a picker
 * (many TVs) there is no choice: downloads go to the public Downloads directory.
 */
object DownloadFolder {
    private const val TAG = "DownloadFolder"
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME = "primary"
    /** The Documents directory of the primary storage, a root of its own */
    private const val HOME_ROOT = "home"
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"
    private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Some app (not the stubs of Android TV) lets the user choose a folder */
    fun pickerAvailable(context: Context): Boolean =
        DownloadFileActions.hasApp(context, Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))

    /** The folder of the settings, whether it is usable or not */
    fun chosen(config: Config): Uri? = config.downloadFolderUri?.let(Uri::parse)

    /** The chosen folder while the app may still write there and it is there; null for the Downloads directory */
    fun usable(context: Context, config: Config): Uri? =
        chosen(config)?.takeIf { granted(context, it) && present(context, it) }

    /** The app still holds the grant to write the folder (revoked grants and restored backups do not come back) */
    fun granted(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** The folder is there: not deleted, its drive not removed. Blocking */
    fun present(context: Context, tree: Uri): Boolean = try {
        DownloadFiles.exists(context, folderDocument(tree).toString())
    } catch (e: IllegalArgumentException) {
        false
    }

    /** Keeps the grant of the folder the picker gave and makes it the folder of downloads. @return false if it fails */
    fun set(context: Context, config: Config, tree: Uri): Boolean {
        try {
            context.contentResolver.takePersistableUriPermission(tree, GRANT_FLAGS)
        } catch (e: SecurityException) {
            Log.w(TAG, "No grant for $tree", e)
            return false
        }
        val old = chosen(config)
        if (old != null && old != tree) release(context, old)
        config.setDownloadFolder(tree.toString(), labelOf(context, tree))
        return true
    }

    /** Downloads go to the Downloads directory again */
    fun reset(context: Context, config: Config) {
        chosen(config)?.let { release(context, it) }
        config.setDownloadFolder(null, null)
    }

    private fun release(context: Context, tree: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(tree, GRANT_FLAGS)
        } catch (e: SecurityException) {
            Log.w(TAG, "Can not release $tree", e)
        }
    }

    /** The document of the folder itself */
    fun folderDocument(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /**
     * Creates the file [name] in the folder. The type is given by the extension of the name when it has a known
     * one (providers add the extension of the type to a name that does not end with it). The provider renames
     * the file if the name is taken: see [displayName]. Blocking
     */
    fun createFile(context: Context, tree: Uri, name: String, mimeType: String?): Uri {
        val fileName = name.ifEmpty { "download" }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val type = if (extension.isEmpty()) mimeType ?: DEFAULT_MIME_TYPE
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME_TYPE
        return DocumentsContract.createDocument(context.contentResolver, folderDocument(tree), type, fileName)
            ?: throw IOException("Can not create $fileName in $tree")
    }

    /** The name of a document, null if it can not be read. Blocking */
    fun displayName(context: Context, document: Uri): String? = try {
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** How the settings show the folder: the storage and the path on it, or the name the provider gives */
    fun labelOf(context: Context, tree: Uri): String {
        val name = displayName(context, folderDocument(tree)) ?: tree.lastPathSegment ?: tree.toString()
        val (volume, path) = volumeOf(context, tree) ?: return name
        val storage = volume?.getDescription(context) ?: return name
        return if (path.isEmpty()) storage else "$storage/$path"
    }

    /** The free space where the folder is, null when it is not known (not a folder of the device storage) */
    fun freeSpace(context: Context, tree: Uri): Long? {
        val (volume, _) = volumeOf(context, tree) ?: return null
        val directory = volume?.let { directoryOf(it) } ?: return null
        return try {
            StatFs(directory.path).availableBytes
        } catch (e: Exception) {
            null
        }
    }

    /** Shows the folder in the file manager of the system, if it can do that */
    fun viewIntent(tree: Uri): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(folderDocument(tree), DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * The storage volume of a folder of the device storage (documents of `volume:path`) and the path of the
     * folder on it; null for folders of other providers
     */
    private fun volumeOf(context: Context, tree: Uri): Pair<StorageVolume?, String>? {
        if (tree.authority != EXTERNAL_STORAGE) return null
        val documentId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: Exception) {
            return null
        }
        val root = documentId.substringBefore(':')
        var path = documentId.substringAfter(':', "")
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val volume = when (root) {
            PRIMARY_VOLUME, HOME_ROOT -> storageManager.storageVolumes.firstOrNull { it.isPrimary }
            else -> storageManager.storageVolumes.firstOrNull { it.uuid.equals(root, ignoreCase = true) }
        }
        if (root == HOME_ROOT) path = listOf(Environment.DIRECTORY_DOCUMENTS, path).filter { it.isNotEmpty() }.joinToString("/")
        return volume to path
    }

    private fun directoryOf(volume: StorageVolume): File? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> volume.directory
        @Suppress("DEPRECATION")
        volume.isPrimary -> Environment.getExternalStorageDirectory()
        volume.uuid != null -> File("/storage/${volume.uuid}")
        else -> null
    }
}
