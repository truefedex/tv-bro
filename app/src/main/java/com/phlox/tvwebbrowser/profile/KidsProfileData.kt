package com.phlox.tvwebbrowser.profile

import android.content.Context
import android.util.Log
import com.phlox.tvwebbrowser.AppContainer
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the kids profile keeps on the device: its database (tabs, history, bookmarks, site settings), the files
 * of its tabs, the data of its pages, which the engine keeps ([WebEngineFactory.deleteKidsData]), and the rows of
 * its downloads that ended (in the database of the main profile; the files stay: an app may be installed). Deleted
 * from the main profile (Settings), after the pending writes of tabs (the kids profile may have been shown a
 * moment ago), with its database closed.
 */
object KidsProfileData {
    private const val TAG = "KidsProfileData"

    /** The kids profile was used on this device (its database is there) */
    fun exists(context: Context): Boolean = context.getDatabasePath(AppDatabase.KIDS_DATABASE_NAME).exists()

    suspend fun delete(context: Context) {
        val app = context.applicationContext
        val container = TVBro.container
        container.tabWrites.awaitPending()
        container.closeKidsDatabase()
        container.mainData.downloads.deleteEndedOfKids()
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
}
