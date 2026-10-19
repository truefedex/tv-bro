package com.phlox.tvwebbrowser.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress

/**
 * Finding TVs in the local network with DNS-SD (NSD): a TV advertises its server while it listens, named
 * after the id of its installation, so a phone finds a paired TV whose address or port changed.
 */
class SyncDiscovery(context: Context) {
    companion object {
        private const val TAG = "SyncDiscovery"
        const val SERVICE_TYPE = "_tvbro._tcp."
        private const val NAME_PREFIX = "tvbro-"
        private const val FIND_TIMEOUT = 8_000L
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null

    /** The TV tells where its server is (until [stopAdvertising]) */
    fun advertise(deviceId: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = NAME_PREFIX + deviceId
            serviceType = SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "advertised as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "not advertised: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "not advertised", e)
            registration = null
        }
    }

    fun stopAdvertising() {
        val listener = registration ?: return
        registration = null
        try {
            nsd.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "unregister", e)
        }
    }

    /** Where the TV with the [deviceId] is, if it is found in a few seconds */
    suspend fun find(deviceId: String): List<InetSocketAddress> {
        val found = CompletableDeferred<NsdServiceInfo>()
        val name = NAME_PREFIX + deviceId
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                Log.d(TAG, "seen ${info.serviceName}")
                if (info.serviceName == name) found.complete(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "looking for $name")
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                found.cancel()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed", e)
            return emptyList()
        }
        try {
            return withTimeoutOrNull(FIND_TIMEOUT) {
                val service = try {
                    found.await()
                } catch (e: Exception) {
                    return@withTimeoutOrNull null
                }
                resolve(service)
            } ?: emptyList()
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (e: Exception) {
            }
        }
    }

    @Suppress("DEPRECATION") //resolveService and host still work; their replacements need API 34
    private suspend fun resolve(service: NsdServiceInfo): List<InetSocketAddress> {
        val resolved = CompletableDeferred<NsdServiceInfo?>()
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed: $errorCode")
                resolved.complete(null)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                resolved.complete(info)
            }
        })
        val info = resolved.await() ?: return emptyList()
        val host = info.host ?: return emptyList()
        Log.d(TAG, "found ${info.serviceName} at ${host.hostAddress}:${info.port}")
        return listOf(InetSocketAddress(host, info.port))
    }
}
