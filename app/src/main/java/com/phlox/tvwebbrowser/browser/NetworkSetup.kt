package com.phlox.tvwebbrowser.browser

import androidx.annotation.MainThread
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.net.AppDns
import com.phlox.tvwebbrowser.net.AppProxy
import com.phlox.tvwebbrowser.net.DnsSettings
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the proxy ([AppProxy]) and the DNS ([AppDns]) of the process and of the engine the same as the settings
 * ([Config.proxy], [Config.dns]). Nothing here touches the engine before it is initialized: the WebView engine
 * must not be loaded before the incognito process gave it its data directory, and the engine applies
 * [AppProxy.settings] and [AppDns.settings] itself in `initialize`.
 */
object NetworkSetup {
    private val applying = Mutex()

    /** Called first when the app starts: the app and the engine follow the settings */
    fun start(config: Config) {
        AppProxy.install()
        AppProxy.settings = config.proxy
        AppDns.settings = dnsInUse(config.proxy, config.dns)
    }

    /**
     * The proxy or the DNS settings changed: once the address of the proxy is complete (or the proxy is turned
     * off) the app and the engine go through the new proxy and DNS, and the current page loads again through them
     */
    @MainThread
    suspend fun apply(config: Config, controller: BrowserController) = applying.withLock {
        val proxy = config.proxy
        val dns = dnsInUse(proxy, config.dns)
        if (proxy == AppProxy.settings && dns == AppDns.settings) return@withLock
        if (proxy != AppProxy.settings) {
            AppProxy.settings = proxy
            WebEngineFactory.applyProxy(proxy)
        }
        if (dns != AppDns.settings) {
            AppDns.settings = dns
            WebEngineFactory.applyDns(dns)
        }
        controller.reload()
    }

    /** A proxy finds the sites itself (SOCKS5 with remote DNS, HTTP with CONNECT), so the DNS is not used then */
    fun dnsInUse(proxy: ProxySettings?, dns: DnsSettings?): DnsSettings? = if (proxy != null) null else dns
}
