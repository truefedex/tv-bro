package com.phlox.tvwebbrowser.utils

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Handles input that may arrive as [KeyEvent]s, as [MotionEvent]s, or through both channels.
 *
 * - [dispatchKeyEvent] forwards allowed framework [KeyEvent]s to [onEmulatedKeyEvent].
 * - [dispatchGenericMotionEvent] translates selected motion axes/buttons into emulated key events
 *   and forwards them to [onEmulatedKeyEvent].
 *
 * To avoid duplicate handling when the same physical action is delivered through both channels,
 * the adapter keeps a small history of recently forwarded events and suppresses consecutive
 * duplicates with the same (eventTime, action, keyCode) signature.
 *
 * Motion-to-key translation still uses dead-zone and discretization to avoid axis spam.
 * Axis translation can be turned off via the `motionAxesTranslationEnabled` callback (e.g. user setting).
 *
 * When [isSoftwareKeyboardVisible] returns true, DPAD navigation input is ignored (not forwarded).
 */
class DPADNavigationEventsAdapter(
    /**
     * Callback invoked for every forwarded framework [KeyEvent] and every emitted emulated
     * [KeyEvent] generated from motion input.
     */
    private val onEmulatedKeyEvent: (KeyEvent) -> Boolean,

    /**
     * Axis absolute value threshold beyond which we consider a stick/hat deflection "intentional".
     */
    private val deadZone: Float = DEFAULT_DEAD_ZONE,

    /**
     * Smaller threshold used to decide when to release a direction after it was previously active.
     * Helps avoid jitter toggling around [deadZone].
     */
    private val releaseDeadZone: Float = DEFAULT_RELEASE_DEAD_ZONE,

    /**
     * Preferred axis pair ordering:
     * - hat axes ([MotionEvent.AXIS_HAT_X]/[MotionEvent.AXIS_HAT_Y]) if they look active
     * - otherwise analog stick axes ([MotionEvent.AXIS_X]/[MotionEvent.AXIS_Y])
     */
    private val preferHatAxes: Boolean = true,

    /**
     * When false, [handleAxes] is skipped (no stick/hat → DPAD translation from the motion channel).
     * Buttons and directional DPAD button bits are unaffected. Use a live lambda so preferences
     * apply without recreating the adapter.
     */
    private val motionAxesTranslationEnabled: () -> Boolean = { true },

    /**
     * When true, DPAD navigation keys and motion-emulated navigation are not forwarded; use while
     * the IME is shown so typing / keyboard navigation is not overridden by cursor logic.
     */
    private val isSoftwareKeyboardVisible: () -> Boolean = { false },
) {
    init {
        require(deadZone >= 0f) { "deadZone must be >= 0" }
        require(releaseDeadZone >= 0f) { "releaseDeadZone must be >= 0" }
    }

    private var lastDirX: Int = 0 // -1 left, 0 neutral, +1 right
    private var lastDirY: Int = 0 // -1 up, 0 neutral, +1 down
    private var lastDpadKeyCode: Int = 0
    private var lastDpadFromButtons: Boolean = false

    private var lastPrimaryDown: Boolean = false

    // Some controllers report DPAD directions as "button bits" instead of (or in addition to)
    // hat/axis values. Android does not guarantee consistent constants across devices/levels,
    // so we detect them via reflection and only use them when present.
    private val dpadButtonBits = DpadButtonBits.fromMotionEvent()

    // Keys DPADNavigationEventsAdapter is responsible for.
    private val allowedKeyCodes: Set<Int> = NavigationReservedShortcutKeyCodes.dpadNavigationKeys

    private fun isKeyAllowed(keyCode: Int): Boolean = allowedKeyCodes.contains(keyCode)

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isKeyAllowed(event.keyCode)) return false

        if (isSoftwareKeyboardVisible()) {
            return false
        }

        val sig = KeyDispatchSignature(event.eventTime, event.action, event.keyCode)
        if (sentEmulatedHistory.lastOrNull() == sig) {
            // Avoid feeding duplicates that may arrive from both dispatch channels.
            // Even if suppressed, report "handled" so callers can consistently consume it.
            return true
        }

        return try {
            onEmulatedKeyEvent(event)
        } catch (t: Throwable) {
            Log.e(TAG, "dispatchKeyEvent: callback failed keyCode=${event.keyCode} action=${event.action}", t)
            false
        } finally {
            recordSentSignature(sig)
        }
    }

    private data class KeyDispatchSignature(val eventTime: Long, val action: Int, val keyCode: Int)

    // Keeps a short history of the signatures that were actually forwarded to `onEmulatedKeyEvent`.
    // This is used for duplicate filtering and also makes it easy to inspect behavior in a debugger.
    private val sentEmulatedHistory = ArrayDeque<KeyDispatchSignature>()
    private val sentEmulatedHistoryMaxSize = 16

    private fun recordSentSignature(sig: KeyDispatchSignature) {
        sentEmulatedHistory.addLast(sig)
        while (sentEmulatedHistory.size > sentEmulatedHistoryMaxSize) {
            sentEmulatedHistory.removeFirst()
        }
    }

    /**
     * Process a MotionEvent (from View/Activity dispatch) and emit emulated KeyEvents as needed.
     *
     * @return true if the event matched the supported joystick/gamepad inputs and we emitted at
     * least one KeyEvent. Callers should typically return this value from their
     * dispatchGenericMotionEvent to prevent the WebView from consuming joystick axis motions.
     */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isSoftwareKeyboardVisible()) {
            return false
        }

        var emittedAnyKey = false

        if (!motionAxesTranslationEnabled()) {
            emittedAnyKey = releaseAxisHeldDirectionIfNeeded(event) || emittedAnyKey
        }

        // Prefer directional D-pad *buttons* over axes if they are actively pressed, otherwise
        // the axis "center jitter" could release a direction while the button is still held.
        val buttonState = event.buttonState
        val dpadButtonsPressedNow =
            dpadButtonBits.supported &&
                (
                    (buttonState and dpadButtonBits.upBit) != 0 ||
                        (buttonState and dpadButtonBits.downBit) != 0 ||
                        (buttonState and dpadButtonBits.leftBit) != 0 ||
                        (buttonState and dpadButtonBits.rightBit) != 0
                    )

        emittedAnyKey = handleDirectionalDpadButtons(event) || emittedAnyKey
        emittedAnyKey = handleButtons(event) || emittedAnyKey

        if (!dpadButtonsPressedNow && motionAxesTranslationEnabled()) {
            emittedAnyKey = handleAxes(event) || emittedAnyKey
        }

        return emittedAnyKey
    }

    /**
     * If axis translation is off but we still hold a direction that came from axes, emit KEY_UP
     * once so navigation does not stay stuck.
     */
    private fun releaseAxisHeldDirectionIfNeeded(event: MotionEvent): Boolean {
        if (lastDpadFromButtons || lastDpadKeyCode == 0) return false
        if (!isKeyAllowed(lastDpadKeyCode)) {
            lastDpadKeyCode = 0
            lastDirX = 0
            lastDirY = 0
            return false
        }
        val emitted = emitKeyEvent(event, action = KeyEvent.ACTION_UP, keyCode = lastDpadKeyCode)
        lastDpadKeyCode = 0
        lastDirX = 0
        lastDirY = 0
        return emitted
    }

    /**
     * Clears internal state (useful if caller wants to drop axis/button tracking).
     * This intentionally does not emit any KEY_UP events.
     */
    fun resetState() {
        lastDirX = 0
        lastDirY = 0
        lastDpadKeyCode = 0
        lastDpadFromButtons = false
        lastPrimaryDown = false
        sentEmulatedHistory.clear()
    }

    private fun handleAxes(event: MotionEvent): Boolean {
        val dead = deadZone
        val releaseDead = minOf(releaseDeadZone, dead) // ensure hysteresis is sane

        val (xAxis, yAxis) = if (preferHatAxes) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val hatLooksActive = abs(hatX) >= dead || abs(hatY) >= dead
            if (hatLooksActive) {
                hatX to hatY
            } else {
                event.getAxisValue(MotionEvent.AXIS_X) to event.getAxisValue(MotionEvent.AXIS_Y)
            }
        } else {
            event.getAxisValue(MotionEvent.AXIS_X) to event.getAxisValue(MotionEvent.AXIS_Y)
        }

        fun axisToDir(axisValue: Float, lastDir: Int): Int {
            return when {
                axisValue >= dead -> 1
                axisValue <= -dead -> -1
                abs(axisValue) <= releaseDead -> 0
                else -> {
                    // Hysteresis band: keep direction only if axis is still on the same side
                    // (prevents "stickiness" when crossing through center with jitter).
                    val sameSign = (axisValue > 0f && lastDir == 1) || (axisValue < 0f && lastDir == -1)
                    if (sameSign) lastDir else 0
                }
            }
        }

        val newDirX = axisToDir(xAxis, lastDirX)
        val newDirY = axisToDir(yAxis, lastDirY)

        val newDpadKeyCode = dpadKeyCodeFromDirs(newDirX, newDirY)
        if (newDpadKeyCode == lastDpadKeyCode) {
            return false
        }

        // Key up old direction (if any) and key down new direction (if any).
        var emittedAny = false
        if (lastDpadKeyCode != 0) {
            if (isKeyAllowed(lastDpadKeyCode)) {
                emittedAny = emitKeyEvent(event, action = KeyEvent.ACTION_UP, keyCode = lastDpadKeyCode) || emittedAny
            }
        }
        lastDirX = newDirX
        lastDirY = newDirY
        var newHeld = 0
        if (newDpadKeyCode != 0 && isKeyAllowed(newDpadKeyCode)) {
            if (emitKeyEvent(event, action = KeyEvent.ACTION_DOWN, keyCode = newDpadKeyCode)) {
                newHeld = newDpadKeyCode
            }
        }
        lastDpadKeyCode = newHeld
        lastDpadFromButtons = false

        return emittedAny
    }

    private fun handleDirectionalDpadButtons(event: MotionEvent): Boolean {
        if (!dpadButtonBits.supported) return false

        val buttonState = event.buttonState
        val upPressed = (buttonState and dpadButtonBits.upBit) != 0
        val downPressed = (buttonState and dpadButtonBits.downBit) != 0
        val leftPressed = (buttonState and dpadButtonBits.leftBit) != 0
        val rightPressed = (buttonState and dpadButtonBits.rightBit) != 0

        val isAnyPressed = upPressed || downPressed || leftPressed || rightPressed
        if (!isAnyPressed) {
            // Release old direction only if we previously emitted it from button DPAD.
            if (lastDpadFromButtons && lastDpadKeyCode != 0) {
                val emitted = if (isKeyAllowed(lastDpadKeyCode)) {
                    emitKeyEvent(event, action = KeyEvent.ACTION_UP, keyCode = lastDpadKeyCode)
                } else {
                    false
                }
                lastDpadKeyCode = 0
                lastDirX = 0
                lastDirY = 0
                lastDpadFromButtons = false
                return emitted
            }
            return false
        }

        val (dirX, dirY, newKeyCode) = dpadKeyCodeFromButtons(upPressed, downPressed, leftPressed, rightPressed)
        if (newKeyCode == 0) {
            // Contradictory button combination: treat as neutral and release if needed.
            if (lastDpadFromButtons && lastDpadKeyCode != 0) {
                val emitted = if (isKeyAllowed(lastDpadKeyCode)) {
                    emitKeyEvent(event, action = KeyEvent.ACTION_UP, keyCode = lastDpadKeyCode)
                } else {
                    false
                }
                lastDpadKeyCode = 0
                lastDirX = 0
                lastDirY = 0
                lastDpadFromButtons = false
                return emitted
            }
            return false
        }

        if (newKeyCode == lastDpadKeyCode) {
            lastDpadFromButtons = true
            return false
        }

        // Key up old direction (if any) and key down new direction.
        var emittedAny = false
        if (lastDpadKeyCode != 0) {
            if (isKeyAllowed(lastDpadKeyCode)) {
                emittedAny = emitKeyEvent(event, action = KeyEvent.ACTION_UP, keyCode = lastDpadKeyCode) || emittedAny
            }
        }
        lastDirX = dirX
        lastDirY = dirY
        var newHeld = 0
        if (newKeyCode != 0 && isKeyAllowed(newKeyCode)) {
            if (emitKeyEvent(event, action = KeyEvent.ACTION_DOWN, keyCode = newKeyCode)) {
                newHeld = newKeyCode
            }
        }
        lastDpadKeyCode = newHeld
        lastDpadFromButtons = newHeld != 0
        return emittedAny
    }

    private fun dpadKeyCodeFromButtons(
        upPressed: Boolean,
        downPressed: Boolean,
        leftPressed: Boolean,
        rightPressed: Boolean
    ): Triple<Int, Int, Int> {
        val dirX = when {
            leftPressed && !rightPressed -> -1
            rightPressed && !leftPressed -> 1
            else -> 0
        }
        val dirY = when {
            upPressed && !downPressed -> -1
            downPressed && !upPressed -> 1
            else -> 0
        }
        val keyCode = when {
            dirX == 0 && dirY == -1 -> KeyEvent.KEYCODE_DPAD_UP
            dirX == 0 && dirY == 1 -> KeyEvent.KEYCODE_DPAD_DOWN
            dirX == -1 && dirY == 0 -> KeyEvent.KEYCODE_DPAD_LEFT
            dirX == 1 && dirY == 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            dirX == -1 && dirY == -1 -> KeyEvent.KEYCODE_DPAD_UP_LEFT
            dirX == 1 && dirY == -1 -> KeyEvent.KEYCODE_DPAD_UP_RIGHT
            dirX == -1 && dirY == 1 -> KeyEvent.KEYCODE_DPAD_DOWN_LEFT
            dirX == 1 && dirY == 1 -> KeyEvent.KEYCODE_DPAD_DOWN_RIGHT
            else -> 0
        }
        return Triple(dirX, dirY, keyCode)
    }

    private fun handleButtons(event: MotionEvent): Boolean {
        val buttonState = event.buttonState

        var emittedAny = false

        fun emitSelection(action: Int): Boolean {
            // Emit multiple "selection" key codes; filtering decides what actually goes through.
            var any = false
            any = emitKeyEvent(event, action = action, keyCode = KeyEvent.KEYCODE_DPAD_CENTER) || any
            any = emitKeyEvent(event, action = action, keyCode = KeyEvent.KEYCODE_ENTER) || any
            any = emitKeyEvent(event, action = action, keyCode = KeyEvent.KEYCODE_NUMPAD_ENTER) || any
            any = emitKeyEvent(event, action = action, keyCode = KeyEvent.KEYCODE_BUTTON_A) || any
            return any
        }

        val primaryPressed = (buttonState and MotionEvent.BUTTON_PRIMARY) != 0
        if (primaryPressed && !lastPrimaryDown) {
            emittedAny = emitSelection(KeyEvent.ACTION_DOWN) || emittedAny
        } else if (!primaryPressed && lastPrimaryDown) {
            emittedAny = emitSelection(KeyEvent.ACTION_UP) || emittedAny
        }
        lastPrimaryDown = primaryPressed

        return emittedAny
    }

    private fun dpadKeyCodeFromDirs(dirX: Int, dirY: Int): Int {
        return when {
            dirX == 0 && dirY == 0 -> 0
            dirX == -1 && dirY == -1 -> KeyEvent.KEYCODE_DPAD_UP_LEFT
            dirX == 1 && dirY == -1 -> KeyEvent.KEYCODE_DPAD_UP_RIGHT
            dirX == -1 && dirY == 1 -> KeyEvent.KEYCODE_DPAD_DOWN_LEFT
            dirX == 1 && dirY == 1 -> KeyEvent.KEYCODE_DPAD_DOWN_RIGHT
            dirX == -1 && dirY == 0 -> KeyEvent.KEYCODE_DPAD_LEFT
            dirX == 1 && dirY == 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            dirX == 0 && dirY == -1 -> KeyEvent.KEYCODE_DPAD_UP
            dirX == 0 && dirY == 1 -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> 0
        }
    }

    private fun emitKeyEvent(
        event: MotionEvent,
        action: Int,
        keyCode: Int,
        repeat: Int = 0,
        flags: Int = 0,
        downTime: Long = event.downTime,
    ): Boolean {
        if (keyCode == 0) return false
        if (!isKeyAllowed(keyCode)) return false

        val sig = KeyDispatchSignature(event.eventTime, action, keyCode)
        if (sentEmulatedHistory.lastOrNull() == sig) {
            // If the same event arrives from both dispatch channels at the same time,
            // drop the duplicate to avoid double-handling.
            // Even if suppressed, report "handled" so callers can consistently consume it.
            return true
        }

        // Preserve the originating [MotionEvent] source (e.g. joystick / gamepad).
        val emulated = KeyEvent(
            downTime,
            event.eventTime,
            action,
            keyCode,
            repeat,
            /* metaState */ 0,
            /* deviceId */ event.deviceId,
            /* scanCode */ 0,
            flags,
            /* source */ event.source
        )
        try {
            onEmulatedKeyEvent(emulated)
            return true
        } catch (t: Throwable) {
            // Input dispatch is timing-sensitive; never let an internal translation error kill the app.
            Log.e(TAG, "Failed to dispatch emulated key=$keyCode action=$action", t)
            return false
        } finally {
            recordSentSignature(sig)
        }
    }

    companion object {
        private const val TAG = "DPADNavigationEventsAdapter"
        private const val DEFAULT_DEAD_ZONE: Float = 0.4f
        private const val DEFAULT_RELEASE_DEAD_ZONE: Float = 0.28f

        private fun readMotionEventStaticInt(fieldName: String): Int? {
            return try {
                val field = MotionEvent::class.java.getField(fieldName)
                field.getInt(null)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private data class DpadButtonBits(
        val supported: Boolean,
        val upBit: Int,
        val downBit: Int,
        val leftBit: Int,
        val rightBit: Int
    ) {
        companion object {
            fun fromMotionEvent(): DpadButtonBits {
                // Field names are not guaranteed. We only need them when the platform provides them.
                val up = readMotionEventStaticInt("BUTTON_DPAD_UP")
                val down = readMotionEventStaticInt("BUTTON_DPAD_DOWN")
                val left = readMotionEventStaticInt("BUTTON_DPAD_LEFT")
                val right = readMotionEventStaticInt("BUTTON_DPAD_RIGHT")
                val supported = up != null || down != null || left != null || right != null
                return DpadButtonBits(
                    supported = supported,
                    upBit = up ?: 0,
                    downBit = down ?: 0,
                    leftBit = left ?: 0,
                    rightBit = right ?: 0
                )
            }
        }
    }
}
