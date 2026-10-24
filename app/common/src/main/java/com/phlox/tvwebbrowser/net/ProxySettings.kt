package com.phlox.tvwebbrowser.net

import android.os.Bundle

/**
 * The proxy the browser goes through (see [AppProxy]): an HTTP proxy (https sites go through it with
 * CONNECT) or a SOCKS5 one. Only an HTTP proxy has a user name and a password: WebView can not log in
 * to a SOCKS proxy.
 */
data class ProxySettings(
    val type: Type,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null
) {
    enum class Type { HTTP, SOCKS5 }

    /** The credentials to answer the proxy with, when it asks (HTTP only) */
    val credentials: Pair<String, String>?
        get() = if (type == Type.HTTP && !username.isNullOrEmpty()) username to (password ?: "") else null

    /** Whether [host] and [port] are the address of this proxy */
    fun isProxyAddress(host: String?, port: Int = this.port): Boolean =
        host != null && host.trim('[', ']').equals(this.host.trim('[', ']'), ignoreCase = true) && port == this.port

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_TYPE, type.name)
        putString(KEY_HOST, host)
        putInt(KEY_PORT, port)
        putString(KEY_USERNAME, username)
        putString(KEY_PASSWORD, password)
    }

    override fun toString(): String = "ProxySettings($type, $host:$port${if (credentials != null) ", with login" else ""})"

    companion object {
        private const val KEY_TYPE = "proxyType"
        private const val KEY_HOST = "proxyHost"
        private const val KEY_PORT = "proxyPort"
        private const val KEY_USERNAME = "proxyUsername"
        private const val KEY_PASSWORD = "proxyPassword"

        const val MIN_PORT = 1
        const val MAX_PORT = 65535

        /**
         * The names (and all their subdomains) that never go through the proxy, apart from names without a
         * dot (simple host names of the local network)
         */
        val BYPASS_DOMAINS = listOf("localhost", "local")

        /** The networks that never go through the proxy: loopback, private and link-local addresses */
        val BYPASS_NETWORKS = listOf(
            "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
            "::1/128", "fc00::/7", "fe80::/10")

        /** The proxy of a [toBundle] in [bundle], or null when it has none */
        fun fromBundle(bundle: Bundle?): ProxySettings? {
            val type = bundle?.getString(KEY_TYPE)?.let { name -> Type.entries.find { it.name == name } } ?: return null
            val host = bundle.getString(KEY_HOST) ?: return null
            return ProxySettings(type, host, bundle.getInt(KEY_PORT), bundle.getString(KEY_USERNAME),
                bundle.getString(KEY_PASSWORD))
        }

        /**
         * The proxy of the settings: null while it is off or its address is not complete. Only an HTTP proxy
         * has a login, and an empty [username] means none.
         */
        fun of(enabled: Boolean, type: Type, host: String, port: Int, username: String, password: String): ProxySettings? {
            if (!enabled || !isValidHost(host) || !isValidPort(port)) return null
            return if (type == Type.HTTP && username.isNotEmpty()) ProxySettings(type, host, port, username, password)
                else ProxySettings(type, host, port)
        }

        /** Whether [port] can be the port of a proxy */
        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

        /** Whether [host] can be the host of a proxy: a name or an address, without a scheme, port or path */
        fun isValidHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            val bare = if (host.startsWith('[') && host.endsWith(']')) host.substring(1, host.length - 1) else host
            if (bare.contains(':')) return bare.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
            if (!bare.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' }) return false
            //only digits and dots: an IPv4 address, as browsers take it
            if (bare.all { it.isDigit() || it == '.' }) {
                val parts = bare.split('.')
                return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.toInt() <= 255 }
            }
            return true
        }
    }
}
