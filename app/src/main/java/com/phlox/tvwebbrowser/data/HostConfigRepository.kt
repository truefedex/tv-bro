package com.phlox.tvwebbrowser.data

import android.util.LruCache
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.dao.HostsDao
import com.phlox.tvwebbrowser.webengine.SiteView
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings of hosts: the popup blocking level, the ads blocking, the site view, the player of fullscreen videos, the
 * page zoom and the favicon file. The looked up configs are kept in memory (also the absence of one), so the main thread reads them
 * without the database. Each setting is written to its own column: one writer does not overwrite another's setting.
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
    private val siteViewSettings = ConcurrentHashMap<String, SiteView>()
    /** The player of fullscreen videos: decided on the main thread when a video goes fullscreen, so in memory too */
    private val builtInPlayerSettings = ConcurrentHashMap<String, Boolean>()
    /** The page zoom: the scripts of pages ask for it on the threads of the engines when a page starts */
    private val pageZoomSettings = ConcurrentHashMap<String, Int>()
    @Volatile
    private var siteSettingsLoaded = false

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

    /** Loads [adBlockSetting]s, [siteViewSetting]s, [builtInPlayerSetting]s and [pageZoomSetting]s, once */
    suspend fun loadSiteSettings() {
        if (siteSettingsLoaded) return
        //settings changed while loading are newer
        dao.adBlockSettings().forEach { adBlockSettings.putIfAbsent(it.hostName, it.adblock) }
        dao.siteViewSettings().forEach { setting ->
            //a view of a newer version is no setting
            SiteView.ofCode(setting.siteView)?.let { siteViewSettings.putIfAbsent(setting.hostName, it) }
        }
        dao.builtInPlayerSettings().forEach { builtInPlayerSettings.putIfAbsent(it.hostName, it.builtInPlayer) }
        dao.pageZoomSettings().forEach { pageZoomSettings.putIfAbsent(it.hostName, it.pageZoom) }
        siteSettingsLoaded = true
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
        dao.setAdBlock(host, adblock)
        reload(host)
    }

    /**
     * The site view setting of the host; null if it has none (or [loadSiteSettings] was not done yet).
     * Can be called from any thread.
     */
    fun siteViewSetting(host: String): SiteView? = siteViewSettings[host]

    /**
     * Sets the site view of the host, null to follow the default. [siteViewSetting] gives the new value
     * right away, before the write is done.
     */
    suspend fun setSiteView(host: String, siteView: SiteView?) {
        if (siteView == null) siteViewSettings.remove(host) else siteViewSettings[host] = siteView
        dao.setSiteView(host, siteView?.code)
        reload(host)
    }

    /**
     * The player of fullscreen videos of the host (true: the built-in one, false: the site's); null if it has none
     * (or [loadSiteSettings] was not done yet)
     */
    fun builtInPlayerSetting(host: String): Boolean? = builtInPlayerSettings[host]

    /**
     * Sets the player of fullscreen videos of the host, null to follow the default. [builtInPlayerSetting] gives the
     * new value right away, before the write is done.
     */
    suspend fun setBuiltInPlayer(host: String, builtInPlayer: Boolean?) {
        if (builtInPlayer == null) builtInPlayerSettings.remove(host) else builtInPlayerSettings[host] = builtInPlayer
        dao.setBuiltInPlayer(host, builtInPlayer)
        reload(host)
    }

    /**
     * The page zoom of the host in percent; null if it has none (or [loadSiteSettings] was not done yet).
     * Can be called from any thread.
     */
    fun pageZoomSetting(host: String): Int? = pageZoomSettings[host]

    /**
     * Sets the page zoom of the host, null for 100%. [pageZoomSetting] gives the new value right away, before the
     * write is done.
     */
    suspend fun setPageZoom(host: String, pageZoom: Int?) {
        if (pageZoom == null) pageZoomSettings.remove(host) else pageZoomSettings[host] = pageZoom
        dao.setPageZoom(host, pageZoom)
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
