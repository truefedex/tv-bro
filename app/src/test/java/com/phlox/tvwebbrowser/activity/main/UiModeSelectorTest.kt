package com.phlox.tvwebbrowser.activity.main

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class UiModeSelectorTest {
    private fun detect(
        uiModeType: Int = Configuration.UI_MODE_TYPE_NORMAL,
        hasLeanback: Boolean = false,
        touchscreen: Int = Configuration.TOUCHSCREEN_FINGER
    ) = UiModeSelector.detect(uiModeType, hasLeanback, touchscreen)

    @Test
    fun touchPhoneOrTabletGetsThePhoneUi() {
        assertEquals(UiMode.PHONE, detect())
    }

    @Test
    fun androidTvGetsTheTvUi() {
        assertEquals(UiMode.TV, detect(uiModeType = Configuration.UI_MODE_TYPE_TELEVISION,
            hasLeanback = true, touchscreen = Configuration.TOUCHSCREEN_NOTOUCH))
    }

    @Test
    fun tvBoxWithTabletFirmwareAndNoTouchPanelGetsTheTvUi() {
        assertEquals(UiMode.TV, detect(touchscreen = Configuration.TOUCHSCREEN_NOTOUCH))
    }

    @Test
    fun leanbackDeviceWithATouchScreenGetsTheTvUi() {
        assertEquals(UiMode.TV, detect(hasLeanback = true))
    }

    @Test
    fun televisionUiModeWithATouchScreenGetsTheTvUi() {
        assertEquals(UiMode.TV, detect(uiModeType = Configuration.UI_MODE_TYPE_TELEVISION))
    }
}
