package com.phlox.tvwebbrowser.webengine.webview

import android.util.Log
import androidx.annotation.UiThread
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.net.ProxySettings
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The proxy of all WebViews of the process ([ProxyController], one for the process). It loads the WebView
 * provider, so it is never used before the incognito process gave WebView its data directory.
 */
object WebViewProxy {
    private const val TAG = "WebViewProxy"

    /** Whether this process changed the proxy of WebView (clearing it otherwise does nothing) */
    private var overridden = false

    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /**
     * The proxy rules of [proxy]: everything but the local addresses goes through it, never directly (there
     * is no `DIRECT` rule after it)
     */
    fun configOf(proxy: ProxySettings): ProxyConfig {
        val scheme = when (proxy.type) {
            ProxySettings.Type.HTTP -> "http"
            ProxySettings.Type.SOCKS5 -> "socks5"
        }
        val host = proxy.host.trim('[', ']').let { if (it.contains(':')) "[$it]" else it }
        val builder = ProxyConfig.Builder()
            .addProxyRule("$scheme://$host:${proxy.port}")
            .bypassSimpleHostnames()
        for (domain in ProxySettings.BYPASS_DOMAINS) {
            builder.addBypassRule(domain)
            builder.addBypassRule("*.$domain")
        }
        ProxySettings.BYPASS_NETWORKS.forEach(builder::addBypassRule)
        return builder.build()
    }

    @UiThread
    suspend fun apply(proxy: ProxySettings?) {
        if (!isSupported()) return
        val controller = ProxyController.getInstance()
        val executor = Executor { it.run() }
        if (proxy == null) {
            if (!overridden) return
            suspendCoroutine { cont -> controller.clearProxyOverride(executor) { cont.resume(Unit) } }
            overridden = false
            Log.i(TAG, "Proxy cleared")
            return
        }
        try {
            //WebView checks the rules itself and refuses them all for one it does not take
            val config = configOf(proxy)
            suspendCoroutine { cont -> controller.setProxyOverride(config, executor) { cont.resume(Unit) } }
            overridden = true
            Log.i(TAG, "Proxy set: $proxy")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "WebView does not take the proxy $proxy", e)
        }
    }
}
