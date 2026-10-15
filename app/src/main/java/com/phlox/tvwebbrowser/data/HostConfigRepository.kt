package com.phlox.tvwebbrowser.data

import android.util.LruCache
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.dao.HostsDao
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings of hosts: the popup blocking level, the ads blocking and the favicon file. The looked up
 * configs are kept in memory (also the absence of one), so the main thread reads them without the database.
 * Each setting is written to its own column: one writer does not overwrite another's setting.
 */
class HostConfigRepository(private val dao: HostsDao) {
    /** [config] is null if the host has no config */
    private class Entry(val config: HostConfig?)

    private val cache = LruCache<String, Entry>(CACHE_SIZE)

    /**
     * Ads blocking settings of all hosts that have one. Engines ask for them on their own threads
     * when requests start, before a config of the page's host could be loaded, so all are in memory.
     */
    private val adBlockSettings = ConcurrentHashMap<String, Boolean>()
    @Volatile
    private var adBlockSettingsLoaded = false

    /**
     * The config if it was looked up already; null if it was not or the host has none.
     */
    fun cached(host: String): HostConfig? = cache.get(host)?.config

    suspend fun find(host: String): HostConfig? {
        cache.get(host)?.let { return it.config }
        return reload(host)
    }

    suspend fun setPopupBlockLevel(host: String, level: Int?) {
        dao.setPopupBlockLevel(host, level)
        reload(host)
    }

    suspend fun setFavicon(host: String, fileName: String?) {
        dao.setFavicon(host, fileName)
        reload(host)
    }

    /** Loads [adBlockSetting]s, once */
    suspend fun loadAdBlockSettings() {
        if (adBlockSettingsLoaded) return
        val stored = dao.adBlockSettings()
        //settings changed while loading are newer
        stored.forEach { adBlockSettings.putIfAbsent(it.hostName, it.adblock) }
        adBlockSettingsLoaded = true
    }

    /**
     * The ads blocking setting of the host; null if it has none (or [loadAdBlockSettings] was not done yet).
     * Can be called from any thread.
     */
    fun adBlockSetting(host: String): Boolean? = adBlockSettings[host]

    /**
     * Sets the ads blocking of the host, null to follow the global setting. [adBlockSetting] gives
     * the new value right away, before the write is done.
     */
    suspend fun setAdBlock(host: String, adblock: Boolean?) {
        if (adblock == null) adBlockSettings.remove(host) else adBlockSettings[host] = adblock
        dao.setAdBlock(host, adblock)
        reload(host)
    }

    private suspend fun reload(host: String): HostConfig? {
        val config = dao.findByHostName(host)
        cache.put(host, Entry(config))
        return config
    }

    companion object {
        private const val CACHE_SIZE = 500
    }
}
