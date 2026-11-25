package com.phlox.tvwebbrowser.webengine.gecko

import android.util.Log
import androidx.annotation.UiThread
import com.phlox.tvwebbrowser.net.DnsSettings
import com.phlox.tvwebbrowser.net.ProxySettings
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntimeSettings
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The DNS of GeckoView: its Trusted Recursive Resolver (DNS over HTTPS) through the runtime settings, in the
 * mode that falls back to the DNS of the device while the server does not answer (pages still open then), and
 * the IP address of the server (`network.trr.bootstrapAddr` on the default branch, not kept), so that its own
 * name is not asked of the DNS of the device. Gecko applies them at once (and clears its DNS cache).
 */
object GeckoDns {
    private const val TAG = "GeckoDns"
    private const val BOOTSTRAP_ADDRESS_PREF = "network.trr.bootstrapAddr"

    /** Whether this process turned the resolver on (turning it off otherwise does nothing) */
    private var applied = false

    /** The mode of the resolver for [dns] */
    fun modeOf(dns: DnsSettings?): Int =
        if (dns == null) GeckoRuntimeSettings.TRR_MODE_OFF else GeckoRuntimeSettings.TRR_MODE_FIRST

    /** The local names are always resolved by the device */
    val excludedDomains: List<String> get() = ProxySettings.BYPASS_DOMAINS

    @UiThread
    suspend fun apply(settings: GeckoRuntimeSettings, dns: DnsSettings?) {
        if (dns == null) {
            if (!applied) return
            settings.setTrustedRecursiveResolverMode(modeOf(null))
            setBootstrapAddress("")
            applied = false
            Log.i(TAG, "DNS of the device")
            return
        }
        //before the mode, so that the first lookups already go to the address
        setBootstrapAddress(dns.bootstrapAddress ?: "")
        settings.setTrustedRecursiveResolverUri(dns.url)
        settings.setTrustedRecursiveResolverExcludedDomains(excludedDomains)
        settings.setTrustedRecursiveResolverMode(modeOf(dns))
        applied = true
        Log.i(TAG, "DNS over HTTPS: $dns")
    }

    private suspend fun setBootstrapAddress(address: String) = suspendCoroutine { cont ->
        GeckoPreferenceController.setGeckoPref(BOOTSTRAP_ADDRESS_PREF, address,
            GeckoPreferenceController.PREF_BRANCH_DEFAULT).then({
            cont.resume(Unit)
            GeckoResult.fromValue<Void>(null)
        }, { e ->
            Log.e(TAG, "Can not set the address of the DNS server", e)
            cont.resume(Unit)
            GeckoResult.fromValue<Void>(null)
        })
    }
}
