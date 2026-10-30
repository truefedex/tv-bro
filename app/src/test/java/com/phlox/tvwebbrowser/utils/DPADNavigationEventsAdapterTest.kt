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
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The direction of the virtual cursor from keys and joystick motion, and the other navigation keys.
 */
@RunWith(RobolectricTestRunner::class)
class DPADNavigationEventsAdapterTest {
    private val directions = mutableListOf<Pair<Float, Float>>()
    private val keys = mutableListOf<Int>()
    private var stickEnabled = true
    private var imeVisible = false
    private val adapter = DPADNavigationEventsAdapter(
        onDirection = { x, y -> directions += x to y },
        onKeyEvent = { keys += it.keyCode; true },
        analogStickNavigationEnabled = { stickEnabled },
        isSoftwareKeyboardVisible = { imeVisible },
    )

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

    private fun key(action: Int, keyCode: Int, deviceId: Int = 1, repeat: Int = 0,
                    source: Int = InputDevice.SOURCE_DPAD, flags: Int = 0) =
        KeyEvent(1000L, 1000L, action, keyCode, repeat, 0, deviceId, 0, flags, source)

    /** A D-pad key as `ViewRootImpl` makes it of joystick motion no view handled */
    private fun synthesized(action: Int, keyCode: Int) =
        key(action, keyCode, source = InputDevice.SOURCE_JOYSTICK, flags = KeyEvent.FLAG_FALLBACK)

    private fun press(keyCode: Int, deviceId: Int = 1) = adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, keyCode, deviceId))
    private fun release(keyCode: Int, deviceId: Int = 1) = adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, keyCode, deviceId))

    private fun assertDirections(vararg expected: Pair<Float, Float>) {
        assertEquals(expected.size, directions.size)
        expected.zip(directions).forEach { (e, a) ->
            assertEquals(e.first, a.first, 0.001f)
            assertEquals(e.second, a.second, 0.001f)
        }
    }

    @Test
    fun hatIsOneDirection() {
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        adapter.dispatchGenericMotionEvent(motion())
        assertDirections(1f to 0f, 0f to 0f)
    }

    @Test
    fun changingTheHatDirectionDoesNotStop() {
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f, hatY = 1f))
        assertDirections(1f to 0f, 1f to 1f)
    }

    @Test
    fun stickKeepsItsAngle() {
        // issue 280: a stick between two directions moved like the nearest D-pad key, or not at all
        adapter.dispatchGenericMotionEvent(motion(x = 0.9f, y = 0.3f))
        val (x, y) = directions.single()
        assertEquals(atan2(0.3f, 0.9f), atan2(y, x), 0.001f)
    }

    @Test
    fun stickSteersWithoutStopping() {
        for (step in 0..10) {
            val angle = step * Math.PI.toFloat() / 20
            adapter.dispatchGenericMotionEvent(motion(x = kotlin.math.cos(angle), y = kotlin.math.sin(angle)))
        }
        assertEquals(11, directions.size)
        assertTrue(directions.none { it.first == 0f && it.second == 0f })
    }

    @Test
    fun stickDeflectionSetsTheSpeed() {
        adapter.dispatchGenericMotionEvent(motion(x = 0.1f, y = 0.1f))
        assertEquals(emptyList<Pair<Float, Float>>(), directions) // inside the dead zone
        adapter.dispatchGenericMotionEvent(motion(x = 0.5f))
        adapter.dispatchGenericMotionEvent(motion(x = 1f))
        val (half, full) = directions.map { hypot(it.first, it.second) }
        assertTrue(half > 0f && half < full)
        assertEquals(1f, full, 0.001f)
    }

    @Test
    fun stickCornerIsClamped() {
        adapter.dispatchGenericMotionEvent(motion(x = 1f, y = -1f))
        val (x, y) = directions.single()
        assertEquals(1f, hypot(x, y), 0.001f)
        assertEquals(-x, y, 0.001f)
    }

    @Test
    fun stickBackToTheCenterStops() {
        adapter.dispatchGenericMotionEvent(motion(x = 1f))
        adapter.dispatchGenericMotionEvent(motion(x = 0.05f))
        assertEquals(0f to 0f, directions.last())
    }

    @Test
    fun dpadAndStickStartingInOneEventGiveTheDpad() {
        adapter.dispatchGenericMotionEvent(motion(hatY = -1f, x = 1f))
        assertDirections(0f to -1f)
    }

    @Test
    fun stickTakesOverFromAHeldHat() {
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f, y = 1f))
        // back to the hat, which is still held, when the stick is let go
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        assertDirections(1f to 0f, 0f to 1f, 1f to 0f)
    }

    @Test
    fun hatTakesOverFromAHeldStick() {
        adapter.dispatchGenericMotionEvent(motion(y = 1f))
        adapter.dispatchGenericMotionEvent(motion(hatX = -1f, y = 1f))
        adapter.dispatchGenericMotionEvent(motion(y = 1f))
        assertDirections(0f to 1f, -1f to 0f, 0f to 1f)
    }

    @Test
    fun theChannelsAreNeverMixed() {
        adapter.dispatchGenericMotionEvent(motion(y = 1f))
        press(KeyEvent.KEYCODE_DPAD_RIGHT, deviceId = 2)
        // the stick moving on does not take over again: it was engaged before the key
        adapter.dispatchGenericMotionEvent(motion(x = -0.7f, y = 0.7f))
        adapter.dispatchGenericMotionEvent(motion(x = -1f))
        release(KeyEvent.KEYCODE_DPAD_RIGHT, deviceId = 2)
        assertDirections(0f to 1f, 1f to 0f, -1f to 0f)
    }

    @Test
    fun hatWorksWithTheStickSettingOff() {
        stickEnabled = false
        adapter.dispatchGenericMotionEvent(motion(hatY = -1f))
        adapter.dispatchGenericMotionEvent(motion())
        assertDirections(0f to -1f, 0f to 0f)
    }

    @Test
    fun stickOffByTheSettingIsStillConsumed() {
        //else the platform would turn the unhandled stick motion into D-pad keys itself
        stickEnabled = false
        assertTrue(adapter.dispatchGenericMotionEvent(motion(x = 1f)))
        assertEquals(emptyList<Pair<Float, Float>>(), directions)
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
        assertEquals(emptyList<Pair<Float, Float>>(), directions)
    }

    @Test
    fun selectKeysAreForwarded() {
        assertTrue(press(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(press(KeyEvent.KEYCODE_A))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_CENTER), keys)
        assertEquals(emptyList<Pair<Float, Float>>(), directions)
    }

    @Test
    fun directionKeysAreNotForwardedAsKeys() {
        assertTrue(press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(release(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(emptyList<Int>(), keys)
        assertDirections(-1f to 0f, 0f to 0f)
    }

    @Test
    fun keysCombineAndReleaseApart() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_UP)
        release(KeyEvent.KEYCODE_DPAD_RIGHT)
        release(KeyEvent.KEYCODE_DPAD_UP)
        assertDirections(1f to 0f, 1f to -1f, 0f to -1f, 0f to 0f)
    }

    @Test
    fun diagonalKeyIsBothAxes() {
        press(KeyEvent.KEYCODE_DPAD_DOWN_LEFT)
        assertDirections(-1f to 1f)
    }

    @Test
    fun keyRepeatsSayTheDirectionAgain() {
        //so a cursor whose gestures were cancelled while the key is held moves on
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1))
        assertDirections(1f to 0f, 1f to 0f)
    }

    @Test
    fun releasingAKeyFallsBackToTheStick() {
        adapter.dispatchGenericMotionEvent(motion(y = 1f))
        press(KeyEvent.KEYCODE_DPAD_RIGHT, deviceId = 2)
        release(KeyEvent.KEYCODE_DPAD_RIGHT, deviceId = 2)
        assertDirections(0f to 1f, 1f to 0f, 0f to 1f)
    }

    @Test
    fun dpadKeysOfAGameControllerMove() {
        val source = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_KEYBOARD
        assertTrue(adapter.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, source = source)))
        assertTrue(adapter.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, source = source)))
        assertDirections(-1f to 0f, 0f to 0f)
    }

    @Test
    fun hatOfAPadThatAlsoSendsDpadKeysWorks() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        release(KeyEvent.KEYCODE_DPAD_LEFT)
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        adapter.dispatchGenericMotionEvent(motion())
        assertDirections(-1f to 0f, 0f to 0f, 1f to 0f, 0f to 0f)
    }

    @Test
    fun aDpadSentAsKeysAndAsTheHatIsOnePress() {
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        release(KeyEvent.KEYCODE_DPAD_RIGHT)
        adapter.dispatchGenericMotionEvent(motion())
        assertDirections(1f to 0f, 0f to 0f)
    }

    @Test
    fun keysSynthesizedFromMotionAreConsumedAndIgnored() {
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        assertTrue(adapter.dispatchKeyEvent(synthesized(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
        assertTrue(adapter.dispatchKeyEvent(synthesized(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)))
        adapter.dispatchGenericMotionEvent(motion())
        assertDirections(1f to 0f, 0f to 0f)

        // and the hat goes on working
        adapter.dispatchGenericMotionEvent(motion(hatY = -1f))
        assertEquals(0f to -1f, directions.last())
    }

    @Test
    fun keysOfTheSoftKeyboardTimeAreNotTaken() {
        // with the soft keyboard shown the platform's keys of the stick move its focus
        imeVisible = true
        assertFalse(adapter.dispatchKeyEvent(synthesized(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
    }

    @Test
    fun softKeyboardReleasesWhatIsHeld() {
        adapter.dispatchGenericMotionEvent(motion(hatX = 1f))
        press(KeyEvent.KEYCODE_DPAD_UP, deviceId = 2)
        imeVisible = true
        assertFalse(adapter.dispatchGenericMotionEvent(motion(hatX = 1f)))
        assertFalse(press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertDirections(1f to 0f, 1f to -1f, 0f to 0f)
    }

    @Test
    fun theLastPressWinsOnItsAxis() {
        //the up of RIGHT never comes (the focus was elsewhere): LEFT must not leave it moving right
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        release(KeyEvent.KEYCODE_DPAD_LEFT)
        assertDirections(1f to 0f, -1f to 0f, 0f to 0f)
    }

    @Test
    fun resetStopsAMovingCursor() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        adapter.resetState()
        release(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertDirections(1f to 0f, 0f to 0f)
    }

    @Test
    fun resetOfAStandingCursorSaysNothing() {
        adapter.resetState()
        assertDirections()
    }

    @Test
    fun resetForgetsTheStick() {
        adapter.dispatchGenericMotionEvent(motion(x = 1f))
        adapter.resetState()
        adapter.dispatchGenericMotionEvent(motion(hatX = -1f))
        assertDirections(1f to 0f, 0f to 0f, -1f to 0f)
    }
}
