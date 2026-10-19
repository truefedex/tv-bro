package com.phlox.tvwebbrowser.model.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.phlox.tvwebbrowser.model.PairedDevice
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDevicesDao {
    /** The most recently seen first */
    @Query("SELECT * FROM paired_devices WHERE kind=:kind ORDER BY last_seen_at DESC, id DESC")
    fun observe(kind: Int): Flow<List<PairedDevice>>

    @Query("SELECT * FROM paired_devices WHERE pairing_id=:pairingId")
    suspend fun findByPairingId(pairingId: String): PairedDevice?

    @Query("SELECT * FROM paired_devices WHERE kind=:kind AND peer_id=:peerId")
    suspend fun findByPeer(kind: Int, peerId: String): List<PairedDevice>

    @Insert
    suspend fun insert(device: PairedDevice): Long

    @Update
    suspend fun update(device: PairedDevice)

    @Query("DELETE FROM paired_devices WHERE id=:id")
    suspend fun delete(id: Long)
}
