package com.phlox.tvwebbrowser.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * What this device tells others about itself: the id of the installation (made once), its name, and the
 * port its server takes when it can (the same one each time, so paired phones find the TV where it was).
 */
class SyncSettings(private val context: Context, private val prefs: SharedPreferences) {
    companion object {
        private const val DEVICE_ID_KEY = "sync_device_id"
        private const val SERVER_PORT_KEY = "sync_server_port"
        private const val USED_PAIRING_KEY = "sync_used_pairing"
    }

    val deviceId: String
        get() = prefs.getString(DEVICE_ID_KEY, null) ?: SyncCrypto.randomId().also {
            prefs.edit().putString(DEVICE_ID_KEY, it).apply()
        }

    /** The name the user gave the device, else its model */
    val deviceName: String
        get() {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            } else {
                null
            }
            return (name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android").take(SyncRequests.MAX_NAME_LENGTH)
        }

    /** 0 until the server got one */
    var serverPort: Int
        get() = prefs.getInt(SERVER_PORT_KEY, 0)
        set(value) {
            prefs.edit().putInt(SERVER_PORT_KEY, value).apply()
        }

    /**
     * Whether a pairing was made with the [link] ([markPairingLinkUsed]). Android gives an activity made
     * again (after its process was killed) the intent it was first started with: a link that paired is
     * not used again, one that failed can be opened again.
     */
    fun isUsedPairingLink(link: SyncLink): Boolean =
        prefs.getString(USED_PAIRING_KEY, null) == SyncCrypto.encode(link.secret)

    fun markPairingLinkUsed(link: SyncLink) {
        prefs.edit().putString(USED_PAIRING_KEY, SyncCrypto.encode(link.secret)).apply()
    }

    /** The IPv4 addresses of this device in local networks (Ethernet and Wi-Fi may both be up) */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress || it.isLinkLocalAddress }
            //link-local addresses (169.254.*) only if there is nothing else
            .sortedBy { if (it.isSiteLocalAddress) 0 else 1 }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }
}
