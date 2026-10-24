package com.phlox.tvwebbrowser.webengine.gecko

import android.util.Log
import androidx.annotation.UiThread
import com.phlox.tvwebbrowser.net.ProxySettings
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoPreferenceController.SetGeckoPreference
import org.mozilla.geckoview.GeckoResult
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The proxy of GeckoView: the `network.proxy.*` preferences, on the default branch (not kept, as
 * `media.av1.enabled`; about:config can still change them). Gecko applies them at once.
 */
object GeckoProxyPrefs {
    private const val TAG = "GeckoProxyPrefs"
    /** `network.proxy.type` of a proxy given by hand */
    private const val TYPE_MANUAL = 1

    /** The default values of the preferences before the first proxy, given back when it is turned off */
    private var originalDefaults: Map<String, Any>? = null

    /** The preferences of [proxy]: everything but the local addresses goes through it, never directly */
    fun prefsFor(proxy: ProxySettings): Map<String, Any> {
        val host = proxy.host.trim('[', ']')
        val http = proxy.type == ProxySettings.Type.HTTP
        val bypass = ProxySettings.BYPASS_DOMAINS.flatMap { listOf(it, ".$it") } +
            ProxySettings.BYPASS_NETWORKS + "<local>"
        return linkedMapOf(
            "network.proxy.type" to TYPE_MANUAL,
            "network.proxy.http" to if (http) host else "",
            "network.proxy.http_port" to if (http) proxy.port else 0,
            "network.proxy.ssl" to if (http) host else "",
            "network.proxy.ssl_port" to if (http) proxy.port else 0,
            "network.proxy.socks" to if (http) "" else host,
            "network.proxy.socks_port" to if (http) 0 else proxy.port,
            "network.proxy.socks_version" to 5,
            //the names of sites are resolved by the SOCKS proxy, not here
            "network.proxy.socks_remote_dns" to !http,
            "network.proxy.socks5_remote_dns" to !http,
            "network.proxy.no_proxies_on" to bypass.joinToString(", "),
            //a proxy that fails gives an error page, the page never loads directly
            "network.proxy.failover_direct" to false
        )
    }

    @UiThread
    suspend fun apply(proxy: ProxySettings?) {
        if (proxy == null) {
            val defaults = originalDefaults ?: return
            set(defaults)
            originalDefaults = null
            Log.i(TAG, "Proxy cleared")
            return
        }
        val prefs = prefsFor(proxy)
        if (originalDefaults == null) originalDefaults = readDefaults(prefs.keys.toList())
        set(prefs)
        Log.i(TAG, "Proxy set: $proxy")
    }

    /** The default values of [names] that have one */
    private suspend fun readDefaults(names: List<String>): Map<String, Any> = suspendCoroutine { cont ->
        GeckoPreferenceController.getGeckoPrefs(names).then({ prefs ->
            val defaults = LinkedHashMap<String, Any>()
            prefs?.forEach { pref -> pref.defaultValue?.let { defaults[pref.pref] = it } }
            cont.resume(defaults)
            GeckoResult.fromValue<Void>(null)
        }, { e ->
            Log.e(TAG, "Can not read the proxy preferences", e)
            cont.resume(emptyMap())
            GeckoResult.fromValue<Void>(null)
        })
    }

    private suspend fun set(prefs: Map<String, Any>) {
        val branch = GeckoPreferenceController.PREF_BRANCH_DEFAULT
        val list = prefs.mapNotNull { (name, value) ->
            when (value) {
                is String -> SetGeckoPreference.setStringPref(name, value, branch)
                is Int -> SetGeckoPreference.setIntPref(name, value, branch)
                is Boolean -> SetGeckoPreference.setBoolPref(name, value, branch)
                else -> null
            }
        }
        suspendCoroutine { cont ->
            GeckoPreferenceController.setGeckoPrefs(list).then({ results ->
                results?.filterValues { !it }?.keys?.takeIf { it.isNotEmpty() }?.let {
                    Log.e(TAG, "Gecko did not take the preferences $it")
                }
                cont.resume(Unit)
                GeckoResult.fromValue<Void>(null)
            }, { e ->
                Log.e(TAG, "Can not set the proxy preferences", e)
                cont.resume(Unit)
                GeckoResult.fromValue<Void>(null)
            })
        }
    }
}
