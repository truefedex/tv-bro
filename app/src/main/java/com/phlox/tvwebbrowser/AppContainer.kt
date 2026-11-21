package com.phlox.tvwebbrowser

import android.content.Context
import com.phlox.tvwebbrowser.adblock.psl.PublicSuffixList
import com.phlox.tvwebbrowser.browser.TabFilesStore
import com.phlox.tvwebbrowser.browser.adblock.AdBlockService
import com.phlox.tvwebbrowser.data.DataRepositories
import com.phlox.tvwebbrowser.service.downloads.DownloadServiceClient
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.SerialWriteQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Application-wide objects that live as long as the process. The browser shows one profile at a time
 * ([AppContext.kidsProfile]); [data] and [tabFiles] are those of that profile.
 */
class AppContainer(context: Context) {
    companion object {
        /** Where the files of the tabs of the kids profile are, in the files and cache directories */
        const val KIDS_DIR = "kids"
    }

    private val appContext = context.applicationContext

    /** For work that must not be cancelled when an activity or a model is destroyed */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tabs database and files writes */
    val tabWrites = SerialWriteQueue(appScope)

    /** Commands to the download service */
    val downloadService by lazy { DownloadServiceClient(appContext) }

    /** Engine states and thumbnails of the tabs of the main profile (the home screen channels show them) */
    val mainTabFiles = TabFilesStore(context.filesDir, context.cacheDir)

    private val kidsTabFiles = TabFilesStore(File(context.filesDir, KIDS_DIR), File(context.cacheDir, KIDS_DIR))

    /** Engine states and thumbnails of the tabs of the profile shown ([AppContext.kidsProfile]) */
    val tabFiles: TabFilesStore
        get() = if (AppContext.kidsProfile) kidsTabFiles else mainTabFiles

    /** The ad blocker of the browser (started by TVBro) */
    val adBlock by lazy {
        AdBlockService(File(appContext.filesDir, "adblock"), appScope, AppContext.provideConfig(), {
            PublicSuffixList.load(appContext.assets.open("adblock/psl.txt").use { it.readBytes() })
        })
    }

    /** The database of the main profile: also the downloads, which go on whatever profile is shown */
    private val mainDatabase by lazy { AppDatabase.create(appContext, AppDatabase.DATABASE_NAME) }

    /**
     * The kids profile keeps its tabs, history, bookmarks and site settings in a database of its own, so
     * nothing of the main profile can be read there, whatever the query. Opened when the kids profile is
     * shown first; closed only to delete it ([closeKidsDatabase]).
     */
    private var kidsDatabase: AppDatabase? = null

    //read without the lock once made: the ad blocker asks for every request
    @Volatile
    private var mainRepositories: DataRepositories? = null
    @Volatile
    private var kidsRepositories: DataRepositories? = null

    /**
     * Repositories of the main profile, whatever profile is shown: the downloads, the home screen channels.
     * Made again if the database is replaced, that is in tests.
     */
    val mainData: DataRepositories
        get() {
            val database = AppDatabase.instanceForTests ?: mainDatabase
            mainRepositories?.takeIf { it.database === database }?.let { return it }
            return synchronized(this) {
                mainRepositories?.takeIf { it.database === database } ?: DataRepositories(database).also { mainRepositories = it }
            }
        }

    /** Repositories of the profile shown ([AppContext.kidsProfile]); opens its database on the first use */
    val data: DataRepositories
        get() {
            if (!AppContext.kidsProfile) return mainData
            val testDatabase = AppDatabase.instanceForTests
            kidsRepositories?.takeIf { testDatabase == null || it.database === testDatabase }?.let { return it }
            return synchronized(this) {
                val database = testDatabase
                    ?: kidsDatabase ?: AppDatabase.create(appContext, AppDatabase.KIDS_DATABASE_NAME).also { kidsDatabase = it }
                kidsRepositories?.takeIf { it.database === database } ?: DataRepositories(database).also { kidsRepositories = it }
            }
        }

    /** Closes the database of the kids profile if it is open, so its files can be deleted (see `KidsProfileData`) */
    fun closeKidsDatabase() {
        val database = synchronized(this) {
            kidsRepositories = null
            kidsDatabase.also { kidsDatabase = null }
        }
        database?.close()
    }
}
