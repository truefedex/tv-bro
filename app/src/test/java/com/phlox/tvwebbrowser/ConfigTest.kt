package com.phlox.tvwebbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigTest {
    private val config get() = AppContext.provideConfig()

    @Test
    fun adBlockEnabledIsOnByDefault() {
        assertTrue(config.adBlockEnabled)
    }

    @Test
    fun adBlockEnabledFollowsThePrefs() {
        //another process (incognito) writes the same prefs
        config.prefs.edit().putBoolean(Config.ADBLOCK_ENABLED_PREF_KEY, false).commit()
        assertFalse(config.adBlockEnabled)
        config.adBlockEnabled = true
        assertTrue(config.prefs.getBoolean(Config.ADBLOCK_ENABLED_PREF_KEY, false))
    }
}
