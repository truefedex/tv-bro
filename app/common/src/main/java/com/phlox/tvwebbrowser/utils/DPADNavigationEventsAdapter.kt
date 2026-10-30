package com.phlox.tvwebbrowser.utils

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Gives the virtual cursor its navigation input, whichever way the hardware reports it: **one**
 * direction vector ([onDirection]) and the other navigation keys ([onKeyEvent], the selection keys,
 * which `CursorDrawerDelegate` maps onto its press). Mouse buttons and pointer input belong to the
 * page.
 *
 * The vector has both components in -1..1 (left and up are negative) and changes without passing
 * through (0, 0), so the cursor steers instead of stopping; (0, 0) means nothing is held. It comes
 * from one of two channels, never from both at once:
 *
 * - **D-pad.** The direction keys held (remotes, keyboards, game controllers whose D-pad sends
 *   keys) and the hats of game controllers (the D-pad of most pads, reported as motion), whole
 *   steps: a diagonal key or two keys give (±1, ±1). Releasing one of two keys leaves the other.
 * - **Stick.** The left sticks of game controllers, analog: the deflection past a round dead zone,
 *   rescaled to start at 0, with a response curve that leaves small deflections slow for aiming.
 *
 * The channel engaged last (gone from neutral to a direction) is the one followed; while it is
 * neutral the other one is, if it is held.
 *
 * Joystick motion that no view handles is turned into D-pad keys by the platform itself (in
 * `ViewRootImpl`), so [dispatchGenericMotionEvent] reports every joystick event as handled, not
 * only those that changed the direction: else the platform would add its own presses to ours. Keys
 * made of motion all the same (by the platform, which gives them the source of the motion, or as a
 * fallback key of a game controller) are consumed and ignored ([isSynthesizedFromMotion]): the
 * motion they come from is here already.
 *
 * The **stick** can be left out ([analogStickNavigationEnabled], the "Navigate with joystick sticks"
 * setting, for pads whose sticks jitter); that never touches the D-pad channel. While
 * [isSoftwareKeyboardVisible] returns true, navigation input is not taken, so the IME keeps its
 * keys, and what was held is released.
 */
class DPADNavigationEventsAdapter(
    /** Gets the direction vector whenever it changes (and again on key repeats, see [dispatchKeyEvent]). */
    private val onDirection: (x: Float, y: Float) -> Unit,

    /** Gets the navigation keys that are not directions (select). */
    private val onKeyEvent: (KeyEvent) -> Boolean,

    /**
     * When false, only the analog stick is left out, the D-pad keeps working.
     * A live lambda, so the setting applies without recreating the adapter.
     */
    private val analogStickNavigationEnabled: () -> Boolean = { true },

    /** When true, navigation input is not taken, so typing is not overridden by the cursor. */
    private val isSoftwareKeyboardVisible: () -> Boolean = { false },
) {
    /** What the axes of one device hold */
    private class DeviceAxes(
        /** The radial dead zone of its stick */
        val stickDeadZone: Float,
    ) {
        var hatX = 0 // -1 left, 0 neutral, +1 right
        var hatY = 0 // -1 up, 0 neutral, +1 down
        var stickX = 0f
        var stickY = 0f
    }

    /** The direction keys held now */
    private val heldKeys = mutableSetOf<Int>()

    private val devices = HashMap<Int, DeviceAxes>()

    private enum class Channel { DPAD, STICK }

    /** The channel engaged last; it is followed while it holds a direction */
    private var activeChannel = Channel.DPAD
    private var dpadEngaged = false
    private var stickEngaged = false

    private var lastX = 0f
    private var lastY = 0f

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in NavigationReservedShortcutKeyCodes.dpadNavigationKeys) return false
        if (isSoftwareKeyboardVisible()) {
            // the up of a held key goes to the IME now: the cursor must not be left moving
            releaseAll()
            return false
        }

        val keyCode = event.keyCode
        if (keyCode !in KEY_DIRECTIONS) {
            return try {
                onKeyEvent(event)
            } catch (t: Throwable) {
                Log.e(TAG, "dispatchKeyEvent: callback failed keyCode=$keyCode action=${event.action}", t)
                false
            }
        }

        // the motion it was made of is handled here, as the hat or the stick
        if (isSynthesizedFromMotion(event)) return true

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // A repeat of a held key says the same again; it is sent anyway, so a cursor that
                // was stopped meanwhile (its gestures cancelled) moves on. A down of a key not held
                // is a press, also as a repeat: the key may have gone down while the focus was elsewhere.
                val repeat = keyCode in heldKeys
                if (!repeat) {
                    // The last press wins on its axes: a held key pointing the other way is let go.
                    // Its up may never come (it went up while the focus was elsewhere), and it
                    // must not move the cursor back once this one is released.
                    val (newX, newY) = KEY_DIRECTIONS.getValue(keyCode)
                    heldKeys.removeAll {
                        val (x, y) = KEY_DIRECTIONS.getValue(it)
                        x * newX < 0 || y * newY < 0
                    }
                    heldKeys += keyCode
                }
                emit(force = repeat)
            }
            KeyEvent.ACTION_UP -> {
                heldKeys.remove(keyCode)
                emit()
            }
        }
        return true
    }

    /**
     * Turns joystick axes into the direction.
     *
     * @return true for every event of a joystick, gamepad or D-pad source (see the class comment),
     * false for other sources (mice, touchpads: their axes are pointer positions) and while the
     * soft keyboard is shown.
     */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isNavigationGenericMotionSource(event.source)) return false
        if (isSoftwareKeyboardVisible()) {
            releaseAll()
            return false
        }

        val axes = devices.getOrPut(event.deviceId) { DeviceAxes(stickDeadZone(event)) }
        axes.hatX = hatToDir(event.getAxisValue(MotionEvent.AXIS_HAT_X), axes.hatX)
        axes.hatY = hatToDir(event.getAxisValue(MotionEvent.AXIS_HAT_Y), axes.hatY)
        // a stick left out by the setting reads as centered; the hat above does not depend on it
        if (analogStickNavigationEnabled()) {
            setStick(axes, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        } else {
            axes.stickX = 0f
            axes.stickY = 0f
        }
        emit()
        return true
    }

    /**
     * Drops what was learned about the devices and what is held. A cursor that was moving is told
     * to stop: the ups of what was held would no longer stop it.
     */
    fun resetState() {
        heldKeys.clear()
        devices.clear()
        emit()
    }

    /** Releases every source and tells the cursor, if anything was held */
    private fun releaseAll() {
        heldKeys.clear()
        devices.clear()
        emit()
    }

    private fun emit(force: Boolean = false) {
        // the D-pad channel: the keys and the hats together (a pad may send its D-pad as both)
        var dpadX = 0f
        var dpadY = 0f
        for (keyCode in heldKeys) {
            val dir = KEY_DIRECTIONS.getValue(keyCode)
            dpadX += dir.first
            dpadY += dir.second
        }
        var stickX = 0f
        var stickY = 0f
        for (axes in devices.values) {
            dpadX += axes.hatX
            dpadY += axes.hatY
            stickX += axes.stickX
            stickY += axes.stickY
        }
        dpadX = dpadX.coerceIn(-1f, 1f)
        dpadY = dpadY.coerceIn(-1f, 1f)
        stickX = stickX.coerceIn(-1f, 1f)
        stickY = stickY.coerceIn(-1f, 1f)

        val dpadNow = dpadX != 0f || dpadY != 0f
        val stickNow = stickX != 0f || stickY != 0f
        // the channel engaged last takes over; the D-pad when both start in one event
        if (stickNow && !stickEngaged) activeChannel = Channel.STICK
        if (dpadNow && !dpadEngaged) activeChannel = Channel.DPAD
        dpadEngaged = dpadNow
        stickEngaged = stickNow
        // the other channel is followed while the active one is neutral
        val useDpad = if (activeChannel == Channel.DPAD) dpadNow || !stickNow else !stickNow
        val x = if (useDpad) dpadX else stickX
        val y = if (useDpad) dpadY else stickY

        if (!force && x == lastX && y == lastY) return
        lastX = x
        lastY = y
        try {
            onDirection(x, y)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to dispatch direction ($x, $y)", t)
        }
    }

    private fun setStick(axes: DeviceAxes, x: Float, y: Float) {
        val deflection = hypot(x, y)
        val deadZone = axes.stickDeadZone
        if (deflection <= deadZone) {
            axes.stickX = 0f
            axes.stickY = 0f
            return
        }
        // past the dead zone the speed starts at 0; the corners of square gates give up to 1.41
        val scaled = ((deflection - deadZone) / (1f - deadZone)).coerceAtMost(1f)
        val factor = scaled.pow(STICK_RESPONSE_EXPONENT) / deflection
        axes.stickX = x * factor
        axes.stickY = y * factor
    }

    private fun hatToDir(axisValue: Float, lastDir: Int): Int = when {
        axisValue >= HAT_THRESHOLD -> 1
        axisValue <= -HAT_THRESHOLD -> -1
        abs(axisValue) <= HAT_RELEASE_THRESHOLD -> 0
        // between the two thresholds the direction stays only while the axis is on its side
        axisValue > 0f && lastDir == 1 -> 1
        axisValue < 0f && lastDir == -1 -> -1
        else -> 0
    }

    /** [STICK_DEAD_ZONE], or the flat range the device reports for its stick if that is larger */
    private fun stickDeadZone(event: MotionEvent): Float {
        val device = event.device ?: return STICK_DEAD_ZONE
        val flatX = device.getMotionRange(MotionEvent.AXIS_X, event.source)?.flat ?: 0f
        val flatY = device.getMotionRange(MotionEvent.AXIS_Y, event.source)?.flat ?: 0f
        // a device that claims a flat range of most of the stick is not believed
        return max(STICK_DEAD_ZONE, max(flatX, flatY)).coerceAtMost(MAX_STICK_DEAD_ZONE)
    }

    companion object {
        private const val TAG = "DPADNavigationEventsAdapter"

        /** Axis value from which a hat direction counts. */
        private const val HAT_THRESHOLD = 0.4f

        /** Smaller threshold for releasing a hat direction, so jitter around [HAT_THRESHOLD] does not toggle it. */
        private const val HAT_RELEASE_THRESHOLD = 0.28f

        /** Deflection of a stick below which it counts as centered (sticks rarely rest at exactly 0). */
        private const val STICK_DEAD_ZONE = 0.2f
        private const val MAX_STICK_DEAD_ZONE = 0.5f

        /** Deflection past the dead zone is raised to this power: slow near the center, full at the edge */
        private const val STICK_RESPONSE_EXPONENT = 2f

        /** The direction keys and their steps */
        private val KEY_DIRECTIONS: Map<Int, Pair<Int, Int>> = hashMapOf(
            KeyEvent.KEYCODE_DPAD_LEFT to (-1 to 0),
            KeyEvent.KEYCODE_DPAD_RIGHT to (1 to 0),
            KeyEvent.KEYCODE_DPAD_UP to (0 to -1),
            KeyEvent.KEYCODE_DPAD_DOWN to (0 to 1),
            KeyEvent.KEYCODE_DPAD_UP_LEFT to (-1 to -1),
            KeyEvent.KEYCODE_DPAD_UP_RIGHT to (1 to -1),
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT to (-1 to 1),
            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT to (1 to 1),
        )

        /**
         * True for a key made of joystick motion, not pressed: `ViewRootImpl` makes D-pad keys of
         * the joystick motion no view handled, with the source of that motion (keys have no joystick
         * source otherwise) and [KeyEvent.FLAG_FALLBACK]; a fallback key of a game controller is no
         * press of its D-pad either.
         */
        fun isSynthesizedFromMotion(event: KeyEvent): Boolean {
            if ((event.source and InputDevice.SOURCE_CLASS_JOYSTICK) != 0) return true
            if ((event.flags and KeyEvent.FLAG_FALLBACK) == 0) return false
            return InputDevice.getDevice(event.deviceId)?.supportsSource(InputDevice.SOURCE_JOYSTICK) == true
        }

        /** True for sources of joystick-style motion; pointer devices (mouse, touchpad) are not. */
        fun isNavigationGenericMotionSource(source: Int): Boolean =
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
    }
}
