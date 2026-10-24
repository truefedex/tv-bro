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
    private val appContext = context.applicationContext

    /** For work that must not be cancelled when an activity or a model is destroyed */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tabs database and files writes */
    val tabWrites = SerialWriteQueue(appScope)

    /** Commands to the download service, which runs in its own process */
    val downloadService by lazy { DownloadServiceClient(appContext) }

    /** Engine states and thumbnails of tabs */
    val tabFiles = TabFilesStore(context.filesDir, context.cacheDir)

    /** The ad blocker of the browser processes (started by TVBro; never in the process of downloads) */
    val adBlock by lazy {
        AdBlockService(File(appContext.filesDir, "adblock"), appScope, AppContext.provideConfig(), {
            PublicSuffixList.load(appContext.assets.open("adblock/psl.txt").use { it.readBytes() })
        })
    }

    private val createdDatabase by lazy { AppDatabase.create(appContext) }

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
