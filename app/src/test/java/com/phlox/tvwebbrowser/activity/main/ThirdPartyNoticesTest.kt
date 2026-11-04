package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The notices the app ships: those of the gecko module only in the GeckoView app, every license text there */
@RunWith(RobolectricTestRunner::class)
class ThirdPartyNoticesTest {
    private val assets = RuntimeEnvironment.getApplication().assets
    private val notices = ThirdPartyNotices.load(assets)

    @Test
    fun noticesOfAllModulesAreLoadedByName() {
        val ids = notices.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue(listOf("androidx", "media3", "qrcodegen", "public-suffix-list", "material-icons").all { it in ids })
        assertEquals(BuildConfig.FLAVOR_webengine == "geckoIncluded", "geckoview" in ids)
        assertEquals(notices.sortedBy { it.name.lowercase() }, notices)
    }

    @Test
    fun everyLicenseTextIsThere() {
        for (notice in notices) {
            val text = ThirdPartyNotices.text(assets, notice)
            assertTrue(notice.id, text.contains(notice.license))
            notice.licenseFile?.let {
                assertTrue(notice.id, assets.list(ThirdPartyNotices.LICENSES_DIR)!!.contains(it))
                assertTrue(notice.id, text.length > 1000)
            }
        }
    }

    @Test
    fun optionalFieldsMayBeLeftOut() {
        val notice = ThirdPartyNotices.parse("""[{"id": "x", "name": "X", "license": "Trademarks", "note": "A note."}]""").single()
        assertNull(notice.licenseFile)
        assertNull(notice.url)
        assertFalse(ThirdPartyNotices.text(assets, notice).contains("null"))
        assertEquals("Trademarks\n\nA note.", ThirdPartyNotices.text(assets, notice))
    }
}
