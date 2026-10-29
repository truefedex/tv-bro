package com.phlox.tvwebbrowser.utils

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Gives the virtual cursor **one** stream of navigation [KeyEvent]s ([onEmulatedKeyEvent]),
 * whichever way the hardware reports D-pad input. It deals with navigation keys only: mouse
 * buttons and pointer input belong to the page, and mapping ENTER / BUTTON_A onto "select" is the
 * job of `CursorDrawerDelegate`.
 *
 * - **Remote or keyboard.** Directions come only as [KeyEvent]s, forwarded as they are.
 * - **Game controller.** The hat (the D-pad of most pads) and the left stick come as joystick
 *   [MotionEvent] axes, which this class turns into `KEYCODE_DPAD_*`.
 *
 * Joystick motion that no view handles is turned into D-pad keys by the platform itself (in
 * `ViewRootImpl`), so [dispatchGenericMotionEvent] reports every joystick event as handled, not
 * only those that changed a direction: else the platform would add its own presses to ours. Some
 * pads still send real D-pad keys next to their hat axes; the first directional key of a device
 * proves that, and its hat is not translated from then on ([devicesWithKeyDpad]).
 *
 * Axis translation uses a dead zone with hysteresis to avoid direction spam. The **stick** can be
 * left out ([analogStickNavigationEnabled], the "Navigate with joystick sticks" setting, for pads
 * whose sticks jitter); that never touches the hat, which is a plain D-pad. While
 * [isSoftwareKeyboardVisible] returns true, navigation input is not taken, so the IME keeps its keys.
 */
class DPADNavigationEventsAdapter(
    /** Gets every forwarded [KeyEvent] and every one made from motion input. */
    private val onEmulatedKeyEvent: (KeyEvent) -> Boolean,

    /**
     * When false, only the analog stick is left out of the translation, the hat keeps working.
     * A live lambda, so the setting applies without recreating the adapter.
     */
    private val analogStickNavigationEnabled: () -> Boolean = { true },

    /** When true, navigation input is not taken, so typing is not overridden by the cursor. */
    private val isSoftwareKeyboardVisible: () -> Boolean = { false },
) {
    private var lastDirX: Int = 0 // -1 left, 0 neutral, +1 right
    private var lastDirY: Int = 0 // -1 up, 0 neutral, +1 down

    /** Direction held because *we* made it from axes, or 0. */
    private var heldAxisKeyCode: Int = 0

    /** Device [heldAxisKeyCode] came from, so a press is only ever closed for its own device. */
    private var heldAxisDeviceId: Int = -1

    /** Devices that sent a real directional key, so translating their hat would double presses. */
    private val devicesWithKeyDpad = mutableSetOf<Int>()

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in NavigationReservedShortcutKeyCodes.dpadNavigationKeys) return false
        if (isSoftwareKeyboardVisible()) return false

        if (event.keyCode in DIRECTION_KEY_CODES && devicesWithKeyDpad.add(event.deviceId)) {
            // If we were in a press made from this device's hat, close it: its ACTION_UP would
            // never come now that the hat is ignored.
            releaseHeldAxisDirection(event.deviceId, event.downTime, event.eventTime, event.source)
        }

        return try {
            onEmulatedKeyEvent(event)
        } catch (t: Throwable) {
            Log.e(TAG, "dispatchKeyEvent: callback failed keyCode=${event.keyCode} action=${event.action}", t)
            false
        }
    }

    /**
     * Turns joystick axes into navigation keys.
     *
     * @return true for every event of a joystick, gamepad or D-pad source (see the class comment),
     * false for other sources (mice, touchpads: their axes are pointer positions) and while the
     * soft keyboard is shown.
     */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isNavigationGenericMotionSource(event.source)) return false
        if (isSoftwareKeyboardVisible()) {
            // the cursor must not be left holding a direction whose ACTION_UP can no longer come
            releaseHeldAxisDirection(event.deviceId, event.downTime, event.eventTime, event.source)
            return false
        }
        handleAxes(event)
        return true
    }

    /** Drops what was learned about the devices and the held direction. Emits no ACTION_UP. */
    fun resetState() {
        clearAxisState()
        devicesWithKeyDpad.clear()
    }

    private fun handleAxes(event: MotionEvent) {
        // The hat while it looks active, else the stick. The hat of a device with real D-pad keys
        // reads as neutral.
        val hatIgnored = event.deviceId in devicesWithKeyDpad
        val hatX = if (hatIgnored) 0f else event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = if (hatIgnored) 0f else event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hatActive = abs(hatX) >= DEAD_ZONE || abs(hatY) >= DEAD_ZONE

        // a stick left out by the setting reads as centered; the hat above does not depend on it
        val (axisX, axisY) = when {
            hatActive -> hatX to hatY
            analogStickNavigationEnabled() ->
                event.getAxisValue(MotionEvent.AXIS_X) to event.getAxisValue(MotionEvent.AXIS_Y)
            else -> 0f to 0f
        }

        val newDirX = axisToDir(axisX, lastDirX)
        val newDirY = axisToDir(axisY, lastDirY)
        val newKeyCode = dpadKeyCodeFromDirs(newDirX, newDirY)
        if (newKeyCode == heldAxisKeyCode) return

        releaseHeldAxisDirection(event.deviceId, event.downTime, event.eventTime, event.source)
        lastDirX = newDirX
        lastDirY = newDirY
        if (newKeyCode != 0 &&
            emitKeyEvent(KeyEvent.ACTION_DOWN, newKeyCode, event.downTime, event.eventTime, event.deviceId, event.source)
        ) {
            heldAxisKeyCode = newKeyCode
            heldAxisDeviceId = event.deviceId
        }
    }

    /** Emits ACTION_UP for the direction held from the axes of [deviceId], if any, and clears it. */
    private fun releaseHeldAxisDirection(deviceId: Int, downTime: Long, eventTime: Long, source: Int) {
        val held = heldAxisKeyCode
        if (held == 0 || heldAxisDeviceId != deviceId) return
        clearAxisState()
        emitKeyEvent(KeyEvent.ACTION_UP, held, downTime, eventTime, deviceId, source)
    }

    private fun clearAxisState() {
        lastDirX = 0
        lastDirY = 0
        heldAxisKeyCode = 0
        heldAxisDeviceId = -1
    }

    private fun axisToDir(axisValue: Float, lastDir: Int): Int = when {
        axisValue >= DEAD_ZONE -> 1
        axisValue <= -DEAD_ZONE -> -1
        abs(axisValue) <= RELEASE_DEAD_ZONE -> 0
        // between the two thresholds the direction stays only while the axis is on its side
        axisValue > 0f && lastDir == 1 -> 1
        axisValue < 0f && lastDir == -1 -> -1
        else -> 0
    }

    private fun dpadKeyCodeFromDirs(dirX: Int, dirY: Int): Int = when {
        dirX == -1 && dirY == -1 -> KeyEvent.KEYCODE_DPAD_UP_LEFT
        dirX == 1 && dirY == -1 -> KeyEvent.KEYCODE_DPAD_UP_RIGHT
        dirX == -1 && dirY == 1 -> KeyEvent.KEYCODE_DPAD_DOWN_LEFT
        dirX == 1 && dirY == 1 -> KeyEvent.KEYCODE_DPAD_DOWN_RIGHT
        dirX == -1 -> KeyEvent.KEYCODE_DPAD_LEFT
        dirX == 1 -> KeyEvent.KEYCODE_DPAD_RIGHT
        dirY == -1 -> KeyEvent.KEYCODE_DPAD_UP
        dirY == 1 -> KeyEvent.KEYCODE_DPAD_DOWN
        else -> 0
    }

    private fun emitKeyEvent(action: Int, keyCode: Int, downTime: Long, eventTime: Long, deviceId: Int, source: Int): Boolean {
        // keeps the device and the source (joystick, gamepad) of the motion
        val emulated = KeyEvent(downTime, eventTime, action, keyCode, 0, 0, deviceId, 0, 0, source)
        return try {
            onEmulatedKeyEvent(emulated)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to dispatch emulated key=$keyCode action=$action", t)
            false
        }
    }

    companion object {
        private const val TAG = "DPADNavigationEventsAdapter"

        /** Axis deflection from which a hat or stick direction counts. */
        private const val DEAD_ZONE = 0.4f

        /** Smaller threshold for releasing a direction, so jitter around [DEAD_ZONE] does not toggle it. */
        private const val RELEASE_DEAD_ZONE = 0.28f

        /** The directions; a real one from a device shows that its D-pad sends keys. */
        private val DIRECTION_KEY_CODES: Set<Int> = hashSetOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP_LEFT,
            KeyEvent.KEYCODE_DPAD_UP_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
        )

        /** True for sources of joystick-style motion; pointer devices (mouse, touchpad) are not. */
        fun isNavigationGenericMotionSource(source: Int): Boolean =
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
    }
}
