package com.phlox.tvwebbrowser.webengine.webview

import android.util.Log
import androidx.annotation.UiThread
import com.phlox.tvwebbrowser.net.DnsSettings
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.net.dns.DohResolver
import com.phlox.tvwebbrowser.net.dns.LocalSocksRelay
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The DNS of WebView, which has no setting of its own: WebView goes through [LocalSocksRelay] as through a
 * SOCKS5 proxy ([WebViewProxy], the local addresses still directly), and the relay finds the sites through the
 * DNS-over-HTTPS server. A proxy of the user takes the place of the relay (it finds the sites itself).
 */
object WebViewDns {
    private const val TAG = "WebViewDns"

    private val threadNumber = AtomicInteger()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "dns-relay-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
    }
    private val relay = LocalSocksRelay(executor)
    private var current: DnsSettings? = null

    /** The proxy WebView goes through for [dns]: the relay, started, or null (stopped) without a server */
    @UiThread
    fun relayProxy(dns: DnsSettings?): ProxySettings? {
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
