package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.sync.SyncKeyStore

/**
 * Repositories of one database: the app and the UI get the stored data only through them.
 */
class DataRepositories(val database: AppDatabase) {
    val hostConfigs = HostConfigRepository(database.hostsDao())
    val tabs = TabsRepository(database.tabsDao())
    val history = HistoryRepository(database.historyDao())
    val favorites = FavoritesRepository(database.favoritesDao())
    val downloads = DownloadsRepository(database.downloadDao())
    val pairedDevices = PairedDevicesRepository(database.pairedDevicesDao()) { SyncKeyStore.instance }
}
