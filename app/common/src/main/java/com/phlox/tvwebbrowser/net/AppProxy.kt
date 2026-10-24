package com.phlox.tvwebbrowser.net

import android.util.Log
import java.io.IOException
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * The proxy of the app's own requests (the web engines get it through `WebEngineFactory.applyProxy`).
 * Connections are opened with [open], which gives the proxy to the connection itself: the HTTP client of
 * Android tries a direct connection after the proxies a [ProxySelector] gives fail, but never after a
 * proxy given to the connection. The default selector ([install]) is only for code that opens connections
 * itself (the built-in player).
 */
object AppProxy {
    private const val TAG = "AppProxy"

    /** The proxy of this process; null connects directly. Browser processes follow the settings. */
    @Volatile
    var settings: ProxySettings? = null

    /** The proxies connections were opened with, by their address: the authenticator answers them */
    private val usedProxies = ConcurrentHashMap<String, ProxySettings>()

    private val bypassNetworks: List<Network> by lazy { ProxySettings.BYPASS_NETWORKS.map(Network::parse) }

    /** Makes the default proxy selector and authenticator of this process follow [settings] */
    fun install() {
        ProxySelector.setDefault(Selector)
        Authenticator.setDefault(ProxyAuthenticator)
    }

    /**
     * Opens a connection to [url] through [proxy] (directly for local addresses). The login of the proxy is
     * never put on the request: the request of an https url goes through the tunnel to the server, and so does
     * an http one redirected to https; the [Authenticator] gives it to the proxy alone, when it asks.
     */
    @Throws(IOException::class)
    fun open(url: URL, proxy: ProxySettings? = settings): URLConnection = url.openConnection(proxyFor(url.host, proxy))

    /** The proxy of a connection to [host]: [Proxy.NO_PROXY] without [proxy] and for local addresses */
    fun proxyFor(host: String?, proxy: ProxySettings? = settings): Proxy {
        if (proxy == null || host.isNullOrEmpty() || isBypassed(host)) return Proxy.NO_PROXY
        usedProxies[addressKey(proxy.host, proxy.port)] = proxy
        val type = when (proxy.type) {
            ProxySettings.Type.HTTP -> Proxy.Type.HTTP
            ProxySettings.Type.SOCKS5 -> Proxy.Type.SOCKS
        }
        return Proxy(type, InetSocketAddress.createUnresolved(proxy.host.trim('[', ']'), proxy.port))
    }

    /**
     * Whether [host] is always reached directly: localhost, `.local` names, names without a dot and the
     * addresses of [ProxySettings.BYPASS_NETWORKS]. Names are not resolved.
     */
    fun isBypassed(host: String): Boolean {
        val name = host.trim().trim('[', ']').trimEnd('.').lowercase()
        if (name.isEmpty()) return true
        if (ProxySettings.BYPASS_DOMAINS.any { name == it || name.endsWith(".$it") }) return true
        val isIpv6 = name.contains(':')
        val isIpv4 = !isIpv6 && name.all { it.isDigit() || it == '.' }
        if (!isIpv6 && !isIpv4) return !name.contains('.')
        if (isIpv4) {
            val parts = name.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.toInt() > 255 }) return false
        }
        //a literal address (an IPv6 one in brackets, so that nothing tries to resolve it): no lookup
        val address = try {
            InetAddress.getByName(if (isIpv6) "[$name]" else name)
        } catch (e: Exception) {
            return false
        }
        return bypassNetworks.any { it.contains(address.address) }
    }

    private fun addressKey(host: String, port: Int) = host.trim('[', ']').lowercase() + ":" + port

    private object Selector : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val scheme = uri?.scheme?.lowercase()
            //sockets (the sync with a phone) and other schemes connect directly
            if (scheme != "http" && scheme != "https") return listOf(Proxy.NO_PROXY)
            return listOf(proxyFor(uri.host))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            Log.w(TAG, "Can not connect to the proxy $sa for $uri", ioe)
        }
    }

    /**
     * Answers only the proxies this process connects through, never the servers of sites. The HTTP client of
     * Android asks again on the same thread, without end, while the proxy refuses the login of a tunnel: a
     * request asked for again right after its answer gets none, which fails it.
     */
    private object ProxyAuthenticator : Authenticator() {
        private const val REFUSED_WITHIN_NANOS = 5_000_000_000L
        /** What the thread answered last: the proxy, the login and the url, and when */
        private val lastAnswer = ThreadLocal<Pair<String, Long>?>()

        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestorType != RequestorType.PROXY) return null
            val address = addressKey(requestingHost ?: return null, requestingPort)
            val proxy = usedProxies[address] ?: return null
            val (user, password) = proxy.credentials ?: return null
            val request = "$address $user:${password.hashCode()} $requestingURL"
            val now = System.nanoTime()
            val last = lastAnswer.get()
            if (last != null && last.first == request && now - last.second < REFUSED_WITHIN_NANOS) {
                //the request fails ("Failed to authenticate with proxy")
                lastAnswer.set(null)
                return null
            }
            lastAnswer.set(request to now)
            return PasswordAuthentication(user, password.toCharArray())
        }
    }

    /** A network of addresses given as `address/prefix length` */
    private class Network(private val address: ByteArray, private val prefixLength: Int) {
        fun contains(other: ByteArray): Boolean {
            if (other.size != address.size) return false
            var bits = prefixLength
            var i = 0
            while (bits > 0) {
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((other[i].toInt() and mask) != (address[i].toInt() and mask)) return false
                bits -= 8
                i++
            }
            return true
        }

        companion object {
            fun parse(cidr: String): Network {
                val (address, prefix) = cidr.split('/')
                return Network(InetAddress.getByName(address).address, prefix.toInt())
            }
        }
    }
}
