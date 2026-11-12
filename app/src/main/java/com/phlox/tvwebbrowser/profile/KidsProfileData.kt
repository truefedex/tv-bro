package com.phlox.tvwebbrowser.profile

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.phlox.tvwebbrowser.AppContainer
import com.phlox.tvwebbrowser.AppProcesses
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the kids profile keeps on the device: its database (tabs, history, bookmarks, site settings), the files
 * of its tabs and the data of its pages, which the engine keeps ([WebEngineFactory.deleteKidsData]). Deleted
 * from the main profile (Settings), so the process of the kids profile is ended first: it would write them again.
 */
object KidsProfileData {
    private const val TAG = "KidsProfileData"

    /** The kids profile was used on this device (its database is there) */
    fun exists(context: Context): Boolean = context.getDatabasePath(AppDatabase.KIDS_DATABASE_NAME).exists()

    suspend fun delete(context: Context) {
        val app = context.applicationContext
        endKidsProcess(app)
        withContext(Dispatchers.IO) {
            app.deleteDatabase(AppDatabase.KIDS_DATABASE_NAME)
            //the lock file Room keeps next to the copy of a database
            File(app.cacheDir, AppDatabase.KIDS_DATABASE_NAME + ".lck").delete()
            File(app.filesDir, AppContainer.KIDS_DIR).deleteRecursively()
            File(app.cacheDir, AppContainer.KIDS_DIR).deleteRecursively()
        }
        try {
            WebEngineFactory.deleteKidsData(app)
        } catch (e: Exception) {
            Log.e(TAG, "The engine could not delete the data of the kids profile", e)
        }
    }

    private fun endKidsProcess(context: Context) {
        val name = context.packageName + AppProcesses.KIDS_SUFFIX
        val processes = context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
        for (process in processes) {
            if (process.processName == name && process.pid != Process.myPid()) Process.killProcess(process.pid)
        }
    }
}
