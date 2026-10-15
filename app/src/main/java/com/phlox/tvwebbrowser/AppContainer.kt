package com.phlox.tvwebbrowser

import android.content.Context
import com.phlox.tvwebbrowser.browser.TabFilesStore
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.SerialWriteQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-wide objects that live as long as the process.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** For work that must not be cancelled when an activity or a model is destroyed */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tabs database and files writes */
    val tabWrites = SerialWriteQueue(appScope)

    /** Engine states and thumbnails of tabs */
    val tabFiles = TabFilesStore(context.filesDir, context.cacheDir)

    private val createdDatabase by lazy { AppDatabase.create(appContext) }

    /** Opened (and migrated) on the first use */
    val database: AppDatabase
        get() = AppDatabase.instanceForTests ?: createdDatabase
}
