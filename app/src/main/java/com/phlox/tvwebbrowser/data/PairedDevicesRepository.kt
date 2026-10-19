package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.model.PairedDevice
import com.phlox.tvwebbrowser.model.dao.PairedDevicesDao
import com.phlox.tvwebbrowser.sync.SyncKeyStore
import kotlinx.coroutines.flow.Flow

/**
 * The phones and TVs this device is paired with, and their keys.
 */
class PairedDevicesRepository(private val dao: PairedDevicesDao, private val keys: () -> SyncKeyStore) {
    /** The paired devices of the [kind], the most recently seen first */
    fun observe(kind: Int): Flow<List<PairedDevice>> = dao.observe(kind)

    suspend fun findByPairingId(pairingId: String): PairedDevice? = dao.findByPairingId(pairingId)

    /** The key of the pairing; null if it can not be unwrapped (the data came from another device) */
    fun keyOf(device: PairedDevice): ByteArray? = keys().unwrap(device.wrappedKey)

    /**
     * Saves a new pairing with the [key]. Older pairings with the same device of that kind are removed:
     * pairing again replaces them.
     */
    suspend fun add(pairingId: String, peerId: String, kind: Int, name: String, key: ByteArray,
                    host: String? = null, port: Int = 0, now: Long = System.currentTimeMillis()): PairedDevice {
        for (old in dao.findByPeer(kind, peerId)) {
            dao.delete(old.id)
        }
        val device = PairedDevice(pairingId = pairingId, peerId = peerId, kind = kind, name = name,
            wrappedKey = keys().wrap(key), host = host, port = port, createdAt = now, lastSeenAt = now)
        return device.copy(id = dao.insert(device))
    }

    /** The device was reached (at the [host] and [port], if given): its name may have changed */
    suspend fun markSeen(device: PairedDevice, name: String, host: String? = device.host, port: Int = device.port,
                         now: Long = System.currentTimeMillis()): PairedDevice {
        val updated = device.copy(name = name, host = host, port = port, lastSeenAt = now)
        dao.update(updated)
        return updated
    }

    suspend fun delete(device: PairedDevice) = dao.delete(device.id)
}
