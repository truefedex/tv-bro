package com.phlox.tvwebbrowser.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A device this one is paired with: a phone that sends to this TV ([KIND_PHONE]) or a TV this phone sends
 * to ([KIND_TV]). Both sides keep the same [pairingId] and key; the key is stored wrapped by a key of the
 * Android keystore (see `SyncKeyStore`), so a backup restored on another device does not carry it.
 */
@Entity(tableName = "paired_devices", indices = [Index(value = ["pairing_id"], name = "paired_devices_pairing_idx", unique = true)])
data class PairedDevice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "pairing_id") val pairingId: String,
    /** The id of the installation of the other device */
    @ColumnInfo(name = "peer_id") val peerId: String,
    val kind: Int,
    val name: String,
    /** The pairing key, wrapped */
    @ColumnInfo(name = "wrapped_key") val wrappedKey: String,
    /** The address and the port the TV was reached at last (on a phone) */
    val host: String? = null,
    val port: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long
) {
    companion object {
        const val KIND_PHONE = 0
        const val KIND_TV = 1
    }
}
