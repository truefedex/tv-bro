package com.phlox.tvwebbrowser.utils

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Back navigation from key events, the system back callback and mouse buttons.
 */
@RunWith(RobolectricTestRunner::class)
class BackNavigationEventsAdapterTest {
    private var backs = 0
    private val adapter = BackNavigationEventsAdapter(onEmulatedBackEvent = { backs++ })

    private fun key(action: Int, keyCode: Int = KeyEvent.KEYCODE_BACK, time: Long = 1000L, repeat: Int = 0,
                    flags: Int = 0, source: Int = InputDevice.SOURCE_KEYBOARD) =
        KeyEvent(time, time, action, keyCode, repeat, 0, 0, 0, flags, source)

    private fun mouse(buttonState: Int, time: Long = 1000L): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords()
        return MotionEvent.obtain(time, time, MotionEvent.ACTION_HOVER_MOVE, 1, arrayOf(properties), arrayOf(coords),
            0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    private fun waitLongPress() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 10L))
    }

    @Test
    fun backIsDetectedOnRelease() {
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))
        assertEquals(0, backs)
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
        assertEquals(1, backs)
    }

    @Test
    fun canceledReleaseIsStillBack() {
        //when the system back callback handles Back (seen on Android 16) every release comes canceled
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
    fun consumedPressStaysConsumedWhileTheKeyRepeats() {
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN))
        adapter.consumeCurrentPress()
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 1))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP))
        assertEquals(0, backs)
    }

    @Test
    fun systemBackRightAfterTheKeyIsTheSameBack() {
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, time = SystemClock.uptimeMillis()))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, time = SystemClock.uptimeMillis()))
        adapter.dispatchSystemBackNavigationEvent()
        assertEquals(1, backs)
    }

    @Test
    fun systemBackLaterIsAnotherBack() {
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, time = SystemClock.uptimeMillis()))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, time = SystemClock.uptimeMillis()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        adapter.dispatchSystemBackNavigationEvent()
        assertEquals(2, backs)
    }

    @Test
    fun controllerNeedsLongPressInDirectNavigation() {
        adapter.gameControllersLongPressBForBackNavigation = true
        val pad = InputDevice.SOURCE_GAMEPAD
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, source = pad))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, source = pad))
        waitLongPress()
        assertEquals(0, backs)

        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, time = 5000L, source = pad))
        waitLongPress()
        assertEquals(1, backs)
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, time = 5000L, source = pad))
        assertEquals(1, backs)
    }

    @Test
    fun remoteGoesBackOnShortPressInDirectNavigation() {
        adapter.gameControllersLongPressBForBackNavigation = true
        //remotes send their keys from a keyboard source, which shares a class bit with gamepads
        val remote = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, source = remote))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, source = remote))
        assertEquals(1, backs)
    }

    @Test
    fun fallbackBackOfAShortBIsNotBackInDirectNavigation() {
        //an unhandled B brings a fallback BACK key in the middle of its press
        adapter.gameControllersLongPressBForBackNavigation = true
        val pad = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, source = pad))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, source = pad))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, source = pad))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, source = pad))
        waitLongPress()
        assertEquals(0, backs)
    }

    @Test
    fun fallbackBackOfBIsOneBack() {
        val pad = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, source = pad))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, source = pad))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, source = pad))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, source = pad))
        assertEquals(1, backs)
    }

    @Test
    fun mouseRightButtonIsBackOnRelease() {
        adapter.dispatchGenericMotionEvent(mouse(MotionEvent.BUTTON_SECONDARY))
        assertEquals(0, backs)
        adapter.dispatchGenericMotionEvent(mouse(0))
        assertEquals(1, backs)
    }

    @Test
    fun mouseBackButtonAndItsKeyAreOneBack() {
        //the platform also turns the back button of a mouse into a BACK key
        adapter.dispatchGenericMotionEvent(mouse(MotionEvent.BUTTON_BACK))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, source = InputDevice.SOURCE_MOUSE))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, source = InputDevice.SOURCE_MOUSE))
        adapter.dispatchGenericMotionEvent(mouse(0))
        assertEquals(1, backs)
    }

    @Test
    fun keyOfTheMouseBackButtonFirstIsOneBack() {
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, source = InputDevice.SOURCE_MOUSE))
        adapter.dispatchGenericMotionEvent(mouse(MotionEvent.BUTTON_BACK))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, source = InputDevice.SOURCE_MOUSE))
        adapter.dispatchGenericMotionEvent(mouse(0))
        assertEquals(1, backs)
    }

    @Test
    fun bFromAKeyboardSourceIsStillAControllerKey() {
        adapter.gameControllersLongPressBForBackNavigation = true
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(0, backs)
    }
}
