package com.phlox.tvwebbrowser.net

import java.net.URI

/**
 * The DNS-over-HTTPS server (RFC 8484) pages find the addresses of sites through, in place of the DNS of the
 * device. [bootstrapAddress] is the IP address of the server, so that its own name is not asked of that DNS
 * (null: it is).
 */
data class DnsSettings(val url: String, val bootstrapAddress: String?) {
    /** The servers to choose from; [SYSTEM] is the DNS of the device, [CUSTOM] the address the user gives */
    enum class Provider(val title: String?, val url: String?, val bootstrapAddress: String?) {
        SYSTEM(null, null, null),
        CLOUDFLARE("Cloudflare", "https://cloudflare-dns.com/dns-query", "1.1.1.1"),
        GOOGLE("Google", "https://dns.google/dns-query", "8.8.8.8"),
        ADGUARD("AdGuard", "https://dns.adguard-dns.com/dns-query", "94.140.14.14"),
        QUAD9("Quad9", "https://dns.quad9.net/dns-query", "9.9.9.9"),
        DNS_SB("DNS.SB", "https://doh.dns.sb/dns-query", "185.222.222.222"),
        CUSTOM(null, null, null)
    }

    companion object {
        /** The server of [provider]: null for [Provider.SYSTEM] and for a [customUrl] that is not one */
        fun of(provider: Provider, customUrl: String): DnsSettings? = when (provider) {
            Provider.SYSTEM -> null
            Provider.CUSTOM -> customUrl.trim().takeIf(::isValidUrl)?.let { DnsSettings(it, null) }
            else -> DnsSettings(provider.url!!, provider.bootstrapAddress)
        }

        /** An https address with a host, without spaces (`https://dns.example.com/dns-query`) */
        fun isValidUrl(url: String): Boolean {
            if (url.isEmpty() || url.any { it.isWhitespace() }) return false
            val uri = try {
                URI(url)
            } catch (e: Exception) {
                return false
            }
            return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
        }
    }
}

/**
 * The DNS of the pages of this process: null while they use the DNS of the device, also while a proxy is in
 * use (the proxy finds the sites then). The engines read it in `initialize`; `WebEngineFactory.applyDns`
 * gives them a change.
 */
object AppDns {
    @Volatile
    var settings: DnsSettings? = null
}
