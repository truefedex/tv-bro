package com.phlox.tvwebbrowser.model.dao

import androidx.room.*
import com.phlox.tvwebbrowser.model.HostConfig

/**
 * Each setting has its own update, so writers of different settings do not overwrite each other.
 */
@Dao
interface HostsDao {
    /** The ads blocking setting of a host that has one */
    data class AdBlockSetting(
        @ColumnInfo(name = "host_name") val hostName: String,
        val adblock: Boolean
    )

    /** The site view setting of a host that has one */
    data class SiteViewSetting(
        @ColumnInfo(name = "host_name") val hostName: String,
        /** [com.phlox.tvwebbrowser.webengine.SiteView.code] */
        @ColumnInfo(name = "desktop_view") val siteView: Int
    )

    /** The player of fullscreen videos of a host that has one */
    data class BuiltInPlayerSetting(
        @ColumnInfo(name = "host_name") val hostName: String,
        @ColumnInfo(name = "built_in_player") val builtInPlayer: Boolean
    )

    /** The page zoom of a host that has one */
    data class PageZoomSetting(
        @ColumnInfo(name = "host_name") val hostName: String,
        @ColumnInfo(name = "page_zoom") val pageZoom: Int
    )

    /** A site the site filter lets in (see [HostConfig.allowedAt]) */
    data class AllowedSite(
        @ColumnInfo(name = "host_name") val hostName: String,
        @ColumnInfo(name = "allowed_at") val allowedAt: Long,
        @ColumnInfo(name = "allowed_until") val allowedUntil: Long
    )

    @Query("SELECT * FROM hosts WHERE host_name = :name")
    suspend fun findByHostName(name: String): HostConfig?

    @Query("SELECT host_name, adblock FROM hosts WHERE adblock IS NOT NULL")
    suspend fun adBlockSettings(): List<AdBlockSetting>

    @Query("SELECT host_name, desktop_view FROM hosts WHERE desktop_view IS NOT NULL")
    suspend fun siteViewSettings(): List<SiteViewSetting>

    @Query("SELECT host_name, built_in_player FROM hosts WHERE built_in_player IS NOT NULL")
    suspend fun builtInPlayerSettings(): List<BuiltInPlayerSetting>

    @Query("SELECT host_name, page_zoom FROM hosts WHERE page_zoom IS NOT NULL")
    suspend fun pageZoomSettings(): List<PageZoomSetting>

    @Query("SELECT host_name, allowed_at, allowed_until FROM hosts WHERE allowed_at IS NOT NULL AND allowed_until IS NOT NULL")
    suspend fun allowedSites(): List<AllowedSite>

    @Query("UPDATE hosts SET allowed_at = :at, allowed_until = :until WHERE host_name = :host")
    suspend fun updateAllowed(host: String, at: Long?, until: Long?)

    @Transaction
    suspend fun setAllowed(host: String, at: Long?, until: Long?) {
        insertIfAbsent(HostConfig(host))
        updateAllowed(host, at, until)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: HostConfig): Long

    @Query("UPDATE hosts SET popup_block_level = :level WHERE host_name = :host")
    suspend fun updatePopupBlockLevel(host: String, level: Int?)

    @Query("UPDATE hosts SET favicon = :favicon WHERE host_name = :host")
    suspend fun updateFavicon(host: String, favicon: String?)

    @Query("UPDATE hosts SET adblock = :adblock WHERE host_name = :host")
    suspend fun updateAdBlock(host: String, adblock: Boolean?)

    @Query("UPDATE hosts SET desktop_view = :siteView WHERE host_name = :host")
    suspend fun updateSiteView(host: String, siteView: Int?)

    @Query("UPDATE hosts SET built_in_player = :builtInPlayer WHERE host_name = :host")
    suspend fun updateBuiltInPlayer(host: String, builtInPlayer: Boolean?)

    @Query("UPDATE hosts SET page_zoom = :pageZoom WHERE host_name = :host")
    suspend fun updatePageZoom(host: String, pageZoom: Int?)

    @Transaction
    suspend fun setPopupBlockLevel(host: String, level: Int?) {
        insertIfAbsent(HostConfig(host))
        updatePopupBlockLevel(host, level)
    }

    @Transaction
    suspend fun setFavicon(host: String, favicon: String?) {
        insertIfAbsent(HostConfig(host))
        updateFavicon(host, favicon)
    }

    @Transaction
    suspend fun setAdBlock(host: String, adblock: Boolean?) {
        insertIfAbsent(HostConfig(host))
        updateAdBlock(host, adblock)
    }

    @Transaction
    suspend fun setSiteView(host: String, siteView: Int?) {
        insertIfAbsent(HostConfig(host))
        updateSiteView(host, siteView)
    }

    @Transaction
    suspend fun setBuiltInPlayer(host: String, builtInPlayer: Boolean?) {
        insertIfAbsent(HostConfig(host))
        updateBuiltInPlayer(host, builtInPlayer)
    }

    @Transaction
    suspend fun setPageZoom(host: String, pageZoom: Int?) {
        insertIfAbsent(HostConfig(host))
        updatePageZoom(host, pageZoom)
    }
}
