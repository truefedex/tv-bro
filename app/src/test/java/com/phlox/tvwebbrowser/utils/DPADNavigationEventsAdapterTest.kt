package com.phlox.tvwebbrowser.utils

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Navigation keys of the virtual cursor from keys and joystick motion.
 */
@RunWith(RobolectricTestRunner::class)
class DPADNavigationEventsAdapterTest {
    private val keys = mutableListOf<String>()
    private var stickEnabled = true
    private var imeVisible = false
    private val adapter = DPADNavigationEventsAdapter(
        onEmulatedKeyEvent = { keys += describe(it.action, it.keyCode); true },
        analogStickNavigationEnabled = { stickEnabled },
        isSoftwareKeyboardVisible = { imeVisible },
    )

    //KeyEvent.keyCodeToString gives only numbers under Robolectric
    private val names = mapOf(
        KeyEvent.KEYCODE_DPAD_LEFT to "KEYCODE_DPAD_LEFT",
        KeyEvent.KEYCODE_DPAD_RIGHT to "KEYCODE_DPAD_RIGHT",
        KeyEvent.KEYCODE_DPAD_UP to "KEYCODE_DPAD_UP",
        KeyEvent.KEYCODE_DPAD_DOWN to "KEYCODE_DPAD_DOWN",
        KeyEvent.KEYCODE_DPAD_DOWN_RIGHT to "KEYCODE_DPAD_DOWN_RIGHT",
        KeyEvent.KEYCODE_DPAD_CENTER to "KEYCODE_DPAD_CENTER",
    )

    private fun describe(action: Int, keyCode: Int) =
        (if (action == KeyEvent.ACTION_DOWN) "down " else "up ") + (names[keyCode] ?: keyCode.toString())

    private fun motion(hatX: Float = 0f, hatY: Float = 0f, x: Float = 0f, y: Float = 0f,
                       source: Int = InputDevice.SOURCE_JOYSTICK, deviceId: Int = 1): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0 }
        val coords = MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_HAT_X, hatX)
            setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
            setAxisValue(MotionEvent.AXIS_X, x)
            setAxisValue(MotionEvent.AXIS_Y, y)
        }
        return MotionEvent.obtain(1000L, 1000L, MotionEvent.ACTION_MOVE, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, deviceId, 0, source, 0)
    }

    private fun key(action: Int, keyCode: Int, deviceId: Int = 1) =
        KeyEvent(1000L, 1000L, action, keyCode, 0, 0, deviceId, 0, 0, InputDevice.SOURCE_DPAD)

    @Test
    fun hatIsOnePress() {
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        adapter.dispatchGenericMotionEvent(motion())
        assertEquals(listOf("down KEYCODE_DPAD_RIGHT", "up KEYCODE_DPAD_RIGHT"), keys)
    }

    @Test
    fun changingDirectionReleasesTheOldOne() {
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f, hatY = 1f))
        assertEquals(listOf("down KEYCODE_DPAD_RIGHT", "up KEYCODE_DPAD_RIGHT", "down KEYCODE_DPAD_DOWN_RIGHT"), keys)
    }

    @Test
    fun stickHasHysteresis() {
        adapter.dispatchGenericMotionEvent(motion(x = 0.5f))
        adapter.dispatchGenericMotionEvent(motion(x = 0.3f))
        assertEquals(listOf("down KEYCODE_DPAD_RIGHT"), keys)
        adapter.dispatchGenericMotionEvent(motion(x = 0.2f))
        assertEquals(listOf("down KEYCODE_DPAD_RIGHT", "up KEYCODE_DPAD_RIGHT"), keys)
    }

    @Test
    fun hatWorksWithTheStickSettingOff() {
        stickEnabled = false
        adapter.dispatchGenericMotionEvent(motion(hatY = -1f))
        adapter.dispatchGenericMotionEvent(motion())
        assertEquals(listOf("down KEYCODE_DPAD_UP", "up KEYCODE_DPAD_UP"), keys)
    }

    @Test
    fun stickOffByTheSettingIsStillConsumed() {
        //else the platform would turn the unhandled stick motion into D-pad keys itself
        stickEnabled = false
        assertTrue(adapter.dispatchGenericMotionEvent(motion(x = 1f)))
        assertEquals(emptyList<String>(), keys)
    }

    @Test
    fun joystickMotionIsConsumedWithoutAChange() {
        assertTrue(adapter.dispatchGenericMotionEvent(motion(x = 0.1f)))
        assertTrue(adapter.dispatchGenericMotionEvent(motion(hatX = 1f)))
        assertTrue(adapter.dispatchGenericMotionEvent(motion(hatX = 1f)))
    }

    @Test
    fun mouseMotionIsIgnored() {
        //the axes of a mouse are pointer positions, not a deflected stick
        assertFalse(adapter.dispatchGenericMotionEvent(motion(x = 500f, y = 300f, source = InputDevice.SOURCE_MOUSE)))
        assertEquals(emptyList<String>(), keys)
    }

    @Test
    fun keysAreForwarded() {
        assertTrue(adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertFalse(adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        assertEquals(listOf("down KEYCODE_DPAD_CENTER"), keys)
    }

    @Test
    fun hatOfADeviceWithDpadKeysIsIgnored() {
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        adapter.dispatchGenericMotionEvent(motion(hatX = -1f))
        adapter.dispatchGenericMotionEvent(motion())
        assertEquals(listOf("down KEYCODE_DPAD_LEFT", "up KEYCODE_DPAD_LEFT"), keys)

        //another device's hat still works
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f, deviceId = 2))
        assertEquals("down KEYCODE_DPAD_RIGHT", keys.last())

        //and the stick of the first one too
        adapter.dispatchGenericMotionEvent(motion(y = 1f))
        assertEquals("down KEYCODE_DPAD_DOWN", keys.last())
    }

    @Test
    fun dpadKeyClosesThePressOfItsHat() {
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf("down KEYCODE_DPAD_RIGHT", "up KEYCODE_DPAD_RIGHT", "down KEYCODE_DPAD_RIGHT"), keys)
    }

    @Test
    fun softKeyboardReleasesTheHeldDirection() {
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        imeVisible = true
        assertFalse(adapter.dispatchGenericMotionEvent(motion(hatX = 1f)))
        assertFalse(adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(listOf("down KEYCODE_DPAD_RIGHT", "up KEYCODE_DPAD_RIGHT"), keys)
    }

    @Test
    fun resetForgetsDevicesWithDpadKeys() {
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        adapter.resetState()
        keys.clear()
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        assertEquals(listOf("down KEYCODE_DPAD_RIGHT"), keys)
    }
}
