package com.phlox.tvwebbrowser.net.dns

import android.util.Log
import androidx.annotation.MainThread
import com.phlox.tvwebbrowser.net.DnsSettings
import com.phlox.tvwebbrowser.net.ProxySettings
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The [LocalSocksRelay] of the process, which finds sites through the DNS server in use: WebView, which has no
 * setting of its DNS, goes through it as through a SOCKS5 proxy, and so do the app's own requests
 * (`AppProxy.dnsRelay`) in both apps. A proxy of the user takes its place (it finds the sites itself).
 */
object DnsRelay {
    private const val TAG = "DnsRelay"

    private val threadNumber = AtomicInteger()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "dns-relay-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
    }
    private val relay = LocalSocksRelay(executor)
    private var current: DnsSettings? = null

    /** The proxy that finds sites through [dns]: the relay, started, or null (stopped) without a server */
    @MainThread
    fun proxyFor(dns: DnsSettings?): ProxySettings? {
        if (dns == null) {
            relay.stop()
            relay.resolver = null
            current = null
            return null
        }
        if (dns != current) {
            val resolver = DohResolver(DohResolver.transportOf(dns), executor)
            relay.resolver = resolver::resolve
            //connections to the addresses of the old server go
            if (current != null) relay.dropConnections()
            current = dns
        }
        val port = try {
            relay.start()
        } catch (e: IOException) {
            Log.e(TAG, "Can not start the relay: the DNS of the device is used", e)
            return null
        }
        return ProxySettings(ProxySettings.Type.SOCKS5, LocalSocksRelay.LOOPBACK, port)
    }
}
