package com.phlox.tvwebbrowser.data

import android.util.LruCache
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.dao.HostsDao
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings of hosts: the popup blocking level, the ads blocking, the site view and the favicon file. The looked up
 * configs are kept in memory (also the absence of one), so the main thread reads them without the database.
 * Each setting is written to its own column: one writer does not overwrite another's setting.
 */
class HostConfigRepository(private val dao: HostsDao) {
    /** [config] is null if the host has no config */
    private class Entry(val config: HostConfig?)

    private val cache = LruCache<String, Entry>(CACHE_SIZE)

    /**
     * Ads blocking and site view settings of all hosts that have one. Engines ask for them on their own
     * threads when requests start, before a config of the page's host could be loaded, so all are in memory.
     */
    private val adBlockSettings = ConcurrentHashMap<String, Boolean>()
    private val desktopViewSettings = ConcurrentHashMap<String, Boolean>()
    @Volatile
    private var siteSettingsLoaded = false
    private val adBlockListeners = CopyOnWriteArrayList<Runnable>()

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

    /** Loads [adBlockSetting]s and [desktopViewSetting]s, once */
    suspend fun loadSiteSettings() {
        if (siteSettingsLoaded) return
        //settings changed while loading are newer
        dao.adBlockSettings().forEach { adBlockSettings.putIfAbsent(it.hostName, it.adblock) }
        dao.desktopViewSettings().forEach { desktopViewSettings.putIfAbsent(it.hostName, it.desktopView) }
        siteSettingsLoaded = true
        adBlockListeners.forEach { it.run() }
    }

    /** The ads blocking settings of all hosts that have one; null until [loadSiteSettings] is done */
    fun adBlockSettings(): Map<String, Boolean>? = if (siteSettingsLoaded) HashMap(adBlockSettings) else null

    /** [listener] is called (on the thread of the change) when [adBlockSettings] change or are loaded */
    fun addAdBlockSettingsListener(listener: Runnable) {
        adBlockListeners += listener
    }

    /**
     * The ads blocking setting of the host; null if it has none (or [loadSiteSettings] was not done yet).
     * Can be called from any thread.
     */
    fun adBlockSetting(host: String): Boolean? = adBlockSettings[host]

    /**
     * Sets the ads blocking of the host, null to follow the global setting. [adBlockSetting] gives
     * the new value right away, before the write is done.
     */
    suspend fun setAdBlock(host: String, adblock: Boolean?) {
        if (adblock == null) adBlockSettings.remove(host) else adBlockSettings[host] = adblock
        //before the write: the page reloads right after the call, and engines that decide in a script must know
        adBlockListeners.forEach { it.run() }
        dao.setAdBlock(host, adblock)
        reload(host)
    }

    /**
     * The site view setting of the host (true: desktop); null if it has none (or [loadSiteSettings] was
     * not done yet). Can be called from any thread.
     */
    fun desktopViewSetting(host: String): Boolean? = desktopViewSettings[host]

    /**
     * Sets the site view of the host, null to follow the default. [desktopViewSetting] gives the new value
     * right away, before the write is done.
     */
    suspend fun setDesktopView(host: String, desktopView: Boolean?) {
        if (desktopView == null) desktopViewSettings.remove(host) else desktopViewSettings[host] = desktopView
        dao.setDesktopView(host, desktopView)
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
