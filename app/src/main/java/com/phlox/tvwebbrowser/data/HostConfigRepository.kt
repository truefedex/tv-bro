package com.phlox.tvwebbrowser.data

import android.util.LruCache
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.dao.HostsDao

/**
 * Settings of hosts: the popup blocking level and the favicon file. The looked up configs are kept
 * in memory (also the absence of one), so the main thread reads them without the database.
 * Each setting is written to its own column: one writer does not overwrite another's setting.
 */
class HostConfigRepository(private val dao: HostsDao) {
    /** [config] is null if the host has no config */
    private class Entry(val config: HostConfig?)

    private val cache = LruCache<String, Entry>(CACHE_SIZE)

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

    private suspend fun reload(host: String): HostConfig? {
        val config = dao.findByHostName(host)
        cache.put(host, Entry(config))
        return config
    }

    companion object {
        private const val CACHE_SIZE = 500
    }
}
