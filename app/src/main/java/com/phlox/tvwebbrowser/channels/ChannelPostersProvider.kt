package com.phlox.tvwebbrowser.channels

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * Gives the launcher the posters of the home screen channels (see [ChannelPosters]): read only, files of the
 * posters directory only, and to the system apps (the launcher) and the app itself only, since the posters of
 * tabs show the pages the user has open. Runs in the `:downloads` process (see the manifest), so a launcher
 * that loads a poster does not start the browser.
 */
class ChannelPostersProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        val context = context ?: throw FileNotFoundException()
        val uid = Binder.getCallingUid()
        if (!isAllowedCaller(context, uid)) {
            Log.w(TAG, "openFile: refused to uid $uid (${callingPackage})")
            throw SecurityException("Not allowed")
        }
        val file = posterFile(context, uri) ?: throw FileNotFoundException(uri.toString())
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/webp"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "ChannelPostersProvider"
        private val PATH = Regex("^(bookmarks|tabs|downloads)/[0-9a-f]{1,64}\\.webp$")

        fun authority(context: Context) = context.packageName + ".channelposters"

        fun uriOf(context: Context, kindDir: String, fileName: String): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(kindDir).appendPath(fileName).build()

        /** The file of a poster uri; null for anything but a file name of the posters directory */
        fun posterFile(context: Context, uri: Uri): File? {
            val path = uri.pathSegments.joinToString("/")
            if (!PATH.matches(path)) return null
            return File(ChannelPosters.postersDir(context), path)
        }

        /** The app itself and the apps of the system image (the launcher and its helpers) */
        fun isAllowedCaller(context: Context, uid: Int): Boolean {
            if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return true
            val pm = context.packageManager
            val packages = pm.getPackagesForUid(uid) ?: return false
            return packages.any { pkg ->
                try {
                    pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
}
