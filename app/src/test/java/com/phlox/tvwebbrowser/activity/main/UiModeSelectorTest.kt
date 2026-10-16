package com.phlox.tvwebbrowser.activity.main

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class UiModeSelectorTest {
    private fun detect(
        uiModeType: Int = Configuration.UI_MODE_TYPE_NORMAL,
        hasLeanback: Boolean = false,
        touchscreen: Int = Configuration.TOUCHSCREEN_FINGER,
        smallestScreenWidthDp: Int = 390
    ) = UiModeSelector.detect(uiModeType, hasLeanback, touchscreen, smallestScreenWidthDp)

    @Test
    fun touchPhoneGetsThePhoneUi() {
        assertEquals(UiMode.PHONE, detect())
    }

    @Test
    fun androidTvGetsTheTvUi() {
        assertEquals(UiMode.TV, detect(uiModeType = Configuration.UI_MODE_TYPE_TELEVISION,
            hasLeanback = true, touchscreen = Configuration.TOUCHSCREEN_NOTOUCH, smallestScreenWidthDp = 540))
    }

    @Test
    fun tvBoxWithTabletFirmwareAndNoTouchPanelGetsTheTvUi() {
        //a 1080p screen at xhdpi is as narrow as a phone
        assertEquals(UiMode.TV, detect(touchscreen = Configuration.TOUCHSCREEN_NOTOUCH, smallestScreenWidthDp = 540))
    }

    @Test
    fun leanbackDeviceWithATouchScreenGetsTheTvUi() {
        assertEquals(UiMode.TV, detect(hasLeanback = true))
    }

    @Test
    fun tabletGetsTheTvUiForNow() {
        assertEquals(UiMode.TV, detect(smallestScreenWidthDp = 600))
        assertEquals(UiMode.PHONE, detect(smallestScreenWidthDp = 599))
    }
}
