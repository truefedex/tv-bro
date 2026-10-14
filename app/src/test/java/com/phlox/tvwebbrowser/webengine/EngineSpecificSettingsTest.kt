package com.phlox.tvwebbrowser.webengine

import com.phlox.tvwebbrowser.browser.FakeWebEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings screen shows an engine-specific setting only for an engine that declares it.
 */
@RunWith(RobolectricTestRunner::class)
class EngineSpecificSettingsTest {
    @Test
    fun settingIsNotSupportedByDefault() {
        WebEngineFactory.initializeForTests(FakeWebEngine.provider)
        assertFalse(WebEngineFactory.supportsSetting(EngineSpecificSetting.ALGORITHMIC_DARKENING))
    }

    @Test
    fun settingDeclaredByTheEngineIsSupported() {
        val fake = FakeWebEngine.provider.callback
        WebEngineFactory.initializeForTests(WebEngineProvider("Darkening", object : WebEngineProviderCallback by fake {
            override fun supportsSetting(setting: EngineSpecificSetting) =
                setting == EngineSpecificSetting.ALGORITHMIC_DARKENING
        }))
        assertTrue(WebEngineFactory.supportsSetting(EngineSpecificSetting.ALGORITHMIC_DARKENING))
        WebEngineFactory.initializeForTests(FakeWebEngine.provider)
    }
}
