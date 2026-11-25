package com.phlox.tvwebbrowser.webengine.gecko

import com.phlox.tvwebbrowser.net.DnsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.GeckoRuntimeSettings

class GeckoDnsTest {
    @Test
    fun aServerIsUsedFirstAndTheDeviceWhileItDoesNotAnswer() {
        assertEquals(GeckoRuntimeSettings.TRR_MODE_FIRST, GeckoDns.modeOf(DnsSettings("https://dns.google/dns-query", null)))
        assertEquals(GeckoRuntimeSettings.TRR_MODE_OFF, GeckoDns.modeOf(null))
    }

    @Test
    fun localNamesAreResolvedByTheDevice() {
        assertTrue("localhost" in GeckoDns.excludedDomains)
        assertTrue("local" in GeckoDns.excludedDomains)
    }
}
