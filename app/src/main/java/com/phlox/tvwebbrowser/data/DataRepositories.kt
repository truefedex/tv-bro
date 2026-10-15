package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.singleton.AppDatabase

/**
 * Repositories of one database: the app and the UI get the stored data only through them.
 */
class DataRepositories(val database: AppDatabase) {
    val hostConfigs = HostConfigRepository(database.hostsDao())
}
