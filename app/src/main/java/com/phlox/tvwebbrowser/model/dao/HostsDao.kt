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

    @Query("SELECT * FROM hosts WHERE host_name = :name")
    suspend fun findByHostName(name: String): HostConfig?

    @Query("SELECT host_name, adblock FROM hosts WHERE adblock IS NOT NULL")
    suspend fun adBlockSettings(): List<AdBlockSetting>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: HostConfig): Long

    @Query("UPDATE hosts SET popup_block_level = :level WHERE host_name = :host")
    suspend fun updatePopupBlockLevel(host: String, level: Int?)

    @Query("UPDATE hosts SET favicon = :favicon WHERE host_name = :host")
    suspend fun updateFavicon(host: String, favicon: String?)

    @Query("UPDATE hosts SET adblock = :adblock WHERE host_name = :host")
    suspend fun updateAdBlock(host: String, adblock: Boolean?)

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
}
