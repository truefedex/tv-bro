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
 * Application-wide objects that live as long as the process.
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

    /** Commands to the download service, which runs in its own process */
    val downloadService by lazy { DownloadServiceClient(appContext) }

    /** Engine states and thumbnails of tabs */
    val tabFiles = if (AppContext.kidsProfile) {
        TabFilesStore(File(context.filesDir, KIDS_DIR), File(context.cacheDir, KIDS_DIR))
    } else {
        TabFilesStore(context.filesDir, context.cacheDir)
    }

    /** The ad blocker of the browser processes (started by TVBro; never in the process of downloads) */
    val adBlock by lazy {
        AdBlockService(File(appContext.filesDir, "adblock"), appScope, AppContext.provideConfig(), {
            PublicSuffixList.load(appContext.assets.open("adblock/psl.txt").use { it.readBytes() })
        })
    }

    /**
     * The kids profile keeps its tabs, history, bookmarks and site settings in a database of its own, so
     * nothing of the main profile can be read there, whatever the query
     */
    private val createdDatabase by lazy {
        AppDatabase.create(appContext, if (AppContext.kidsProfile) AppDatabase.KIDS_DATABASE_NAME else AppDatabase.DATABASE_NAME)
    }

    /** Opened (and migrated) on the first use */
    val database: AppDatabase
        get() = AppDatabase.instanceForTests ?: createdDatabase

    private var repositories: DataRepositories? = null

    /** Repositories of [database] (made again if the database is replaced, that is in tests) */
    val data: DataRepositories
        get() = synchronized(this) {
            val database = database
            repositories?.takeIf { it.database === database } ?: DataRepositories(database).also { repositories = it }
        }
}
