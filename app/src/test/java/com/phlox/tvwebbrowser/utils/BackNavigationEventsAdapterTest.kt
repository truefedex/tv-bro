package com.phlox.tvwebbrowser.utils

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Back navigation from key events.
 */
@RunWith(RobolectricTestRunner::class)
class BackNavigationEventsAdapterTest {
    private var backs = 0
    private val adapter = BackNavigationEventsAdapter(onEmulatedBackEvent = { backs++ })

    private fun key(action: Int, keyCode: Int = KeyEvent.KEYCODE_BACK, time: Long = 1000L, repeat: Int = 0,
                    flags: Int = 0, source: Int = InputDevice.SOURCE_KEYBOARD) =
        KeyEvent(time, time, action, keyCode, repeat, 0, 0, 0, flags, source)

    @Test
    fun backIsDetectedOnRelease() {
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))
        assertEquals(0, backs)
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
        assertEquals(1, backs)
    }

    @Test
    fun canceledReleaseIsStillBack() {
        //since Android 13 the release is canceled when the system back callback runs
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED))
        assertEquals(1, backs)
    }

    @Test
    fun consumedPressIsNotBack() {
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))
        adapter.consumeCurrentPress()
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
        assertEquals(0, backs)

        //the next press works
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, time = 5000L))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, time = 5000L))
        assertEquals(1, backs)
    }

    @Test
    fun systemBackRightAfterTheKeyIsTheSameBack() {
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, time = android.os.SystemClock.uptimeMillis()))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, time = android.os.SystemClock.uptimeMillis()))
        adapter.dispatchSystemBackNavigationEvent()
        assertEquals(1, backs)
    }

    @Test
    fun controllerNeedsLongPressInDirectNavigation() {
        adapter.gameControllersLongPressBForBackNavigation = true
        val pad = InputDevice.SOURCE_GAMEPAD
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, source = pad))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, source = pad))
        assertEquals(0, backs)

        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, time = 5000L, source = pad))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, time = 5000L, repeat = 1,
            flags = KeyEvent.FLAG_LONG_PRESS, source = pad))
        assertEquals(1, backs)
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, time = 5000L, source = pad))
        assertEquals(1, backs)
    }
}
