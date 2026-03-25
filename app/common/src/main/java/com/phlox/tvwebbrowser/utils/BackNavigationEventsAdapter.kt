package com.phlox.tvwebbrowser.utils

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.max

/**
 * Detects "back navigation" intent from multiple input channels:
 * - [dispatchSystemBackNavigationEvent] (system back callback)
 * - [dispatchKeyEvent] (framework key events)
 * - [dispatchGenericMotionEvent] (controller button state as motion)
 *
 * This adapter does **not** emit/forward [KeyEvent]s. Instead it invokes [onEmulatedBackEvent]
 * when it decides a "back navigation" should happen.
 *
 * Duplicate delivery is handled via a small cross-channel suppression window: if one channel
 * recently generated BACK, other channels are ignored for a short time.
 */
class BackNavigationEventsAdapter(
    /**
     * Callback invoked when a back navigation intent is detected.
     *
     * By default, the callback is invoked only after a BACK key press and key release are
     * both detected.
     *
     * If [gameControllersLongPressBForBackNavigation] is true, then controller sources
     * ([android.view.InputDevice.SOURCE_JOYSTICK] or [android.view.InputDevice.SOURCE_GAMEPAD])
     * invoke the callback only when a long-press back is detected, not on release.
     */
    private val onEmulatedBackEvent: () -> Unit,

    /**
     * Milliseconds after which a held BACK/B button emits a long-press BACK [KeyEvent].
     */
    private val longPressTimeoutMs: Int = DEFAULT_LONG_PRESS_TIMEOUT_MS,

    /**
     * When true, controller "B/back" short press does not trigger back navigation; long-press does.
     */
    var gameControllersLongPressBForBackNavigation: Boolean = false,
) {
    init {
        require(longPressTimeoutMs > 0) { "longPressTimeoutMs must be > 0" }
    }

    private val debugLogs: Boolean = false
    private fun d(msg: String) {
        if (debugLogs) {
            Log.d(TAG, msg)
        }
    }

    private val allowedKeyCodes: Set<Int> = intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B).toHashSet()

    private fun isKeyAllowed(keyCode: Int): Boolean = allowedKeyCodes.contains(keyCode)

    private var lastBackDown: Boolean = false

    /** [KeyEvent.downTime] of the first emulated BACK [KeyEvent.ACTION_DOWN] while held. */
    private var backKeyDownTime: Long = 0L

    /** True after we emitted the synthetic long-press [KeyEvent]. */
    private var backLongPressEmitted: Boolean = false

    private enum class BackChannel { SYSTEM, KEY, MOTION }

    private fun isControllerSource(source: Int): Boolean {
        return (source and android.view.InputDevice.SOURCE_JOYSTICK) != 0 ||
            (source and android.view.InputDevice.SOURCE_GAMEPAD) != 0
    }

    /**
     * For non-controller sources, callback is fired only after ACTION_DOWN followed by
     * ACTION_UP.
     */
    private var pendingBackDown: Boolean = false

    /**
     * When controller-long-press mode triggers, we should not fire again on release.
     */
    private var controllerLongPressTriggeredSinceDown: Boolean = false

    /**
     * For KeyEvent channel: latch controllerLongPressOnly for the whole press sequence.
     * Some devices change flags/source across repeats; we must stay consistent until ACTION_UP.
     */
    private var keyPressActive: Boolean = false
    private var keyPressControllerLongPressOnly: Boolean = false

    // Timestamp of last BACK ACTION_DOWN emulated/forwarded by each channel (ms, based on uptime).
    private val lastBackDownEventTimeByChannel = LongArray(BackChannel.entries.size) { -1L }

    /**
     * If one channel emits BACK recently, we suppress BACK from other channels within this window.
     * This replaces eventTime+action+keyCode dedupe because those can differ between channels.
     */
    private val suppressOtherChannelsTimeoutMs: Long = 1000L

    private fun shouldSuppress(from: BackChannel, eventTime: Long): Boolean {
        fun recent(other: BackChannel): Boolean {
            val t = lastBackDownEventTimeByChannel[other.ordinal]
            if (t < 0L) return false
            val dt = eventTime - t
            return dt in 0L..suppressOtherChannelsTimeoutMs
        }

        return when (from) {
            BackChannel.SYSTEM -> recent(BackChannel.KEY) || recent(BackChannel.MOTION)
            BackChannel.KEY -> recent(BackChannel.SYSTEM) || recent(BackChannel.MOTION)
            BackChannel.MOTION -> recent(BackChannel.SYSTEM) || recent(BackChannel.KEY)
        }
    }

    private fun markBackDownGenerated(from: BackChannel, eventTime: Long) {
        lastBackDownEventTimeByChannel[from.ordinal] = eventTime
        d("markBackDownGenerated from=$from eventTime=$eventTime")
    }

    fun dispatchSystemBackNavigationEvent(): Boolean {
        val now = SystemClock.uptimeMillis()
        val suppressed = shouldSuppress(BackChannel.SYSTEM, now)
        d("dispatchSystemBackNavigationEvent now=$now suppressed=$suppressed")
        if (!suppressed) {
            // Treat this as: BACK down then BACK up.
            pendingBackDown = true
            markBackDownGenerated(BackChannel.SYSTEM, now)

            // Callback only after both press+release have been detected.
            if (pendingBackDown) {
                pendingBackDown = false
                try {
                    d("dispatchSystemBackNavigationEvent firing onEmulatedBackEvent()")
                    onEmulatedBackEvent()
                } catch (t: Throwable) {
                    Log.e(TAG, "dispatchSystemBackNavigationEvent: onEmulatedBackEvent failed", t)
                }
            }
            return true
        }
        return false
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isKeyAllowed(event.keyCode)) return false
        val suppressed = shouldSuppress(BackChannel.KEY, event.eventTime)
        d("dispatchKeyEvent keyCode=${event.keyCode} action=${event.action} source=${event.source} " +
            "eventTime=${event.eventTime} downTime=${event.downTime} repeatCount=${event.repeatCount} " +
            "isLongPress=${event.isLongPress} flags=${event.flags} suppressed=$suppressed")
        if (suppressed) return false

        return try {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // Latch mode on the first DOWN of a press.
                    if (!keyPressActive && event.repeatCount == 0) {
                        keyPressActive = true
                        keyPressControllerLongPressOnly =
                            gameControllersLongPressBForBackNavigation && isControllerSource(event.source)
                        controllerLongPressTriggeredSinceDown = false
                        pendingBackDown = false
                        d("dispatchKeyEvent latch press: controllerLongPressOnly=$keyPressControllerLongPressOnly")
                    }

                    markBackDownGenerated(BackChannel.KEY, event.eventTime)
                    if (keyPressControllerLongPressOnly) {
                        val isLongPressNow =
                            event.isLongPress || event.repeatCount > 0 || (event.flags and KeyEvent.FLAG_LONG_PRESS) != 0
                        if (isLongPressNow && !controllerLongPressTriggeredSinceDown) {
                            controllerLongPressTriggeredSinceDown = true
                            d("dispatchKeyEvent ACTION_DOWN controllerLongPressOnly: long-press detected -> callback")
                            onEmulatedBackEvent()
                        }
                        // Consume the event even when it's not long-press.
                    } else if (event.repeatCount == 0) {
                        // Non-controller: only treat the first DOWN as "press start".
                        d("dispatchKeyEvent ACTION_DOWN non-controller: pendingBackDown=true")
                        pendingBackDown = true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (keyPressControllerLongPressOnly) {
                        // Don't call callback on release; end long-press tracking.
                        d("dispatchKeyEvent ACTION_UP controllerLongPressOnly: release -> no callback")
                        controllerLongPressTriggeredSinceDown = false
                    } else {
                        if (pendingBackDown) {
                            d("dispatchKeyEvent ACTION_UP non-controller: pendingBackDown -> callback")
                            pendingBackDown = false
                            onEmulatedBackEvent()
                        } else {
                            pendingBackDown = false
                        }
                    }

                    keyPressActive = false
                    keyPressControllerLongPressOnly = false
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "dispatchKeyEvent failed keyCode=${event.keyCode} action=${event.action}", t)
            false
        }
    }

    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val buttonState = event.buttonState
        val backPressedNow =
            (buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_BACK) != 0

        var emittedAny = false
        val suppressed = shouldSuppress(BackChannel.MOTION, event.eventTime)

        val controllerLongPressOnly =
            gameControllersLongPressBForBackNavigation && isControllerSource(event.source)

        d("dispatchGenericMotionEvent buttonState=$buttonState source=${event.source} " +
            "eventTime=${event.eventTime} downTime=${event.downTime} backPressedNow=$backPressedNow " +
            "lastBackDown=$lastBackDown suppressed=$suppressed controllerLongPressOnly=$controllerLongPressOnly")

        if (backPressedNow && !lastBackDown) {
            // Track the hold regardless of suppression so long-press can still be emitted later.
            backKeyDownTime = event.downTime
            backLongPressEmitted = false
            if (!suppressed) {
                markBackDownGenerated(BackChannel.MOTION, event.eventTime)
                if (controllerLongPressOnly) {
                    controllerLongPressTriggeredSinceDown = false
                    // Don't fire callback yet; only on long-press.
                } else {
                    pendingBackDown = true
                }
                emittedAny = true
            }
        } else if (!backPressedNow && lastBackDown) {
            if (!suppressed) {
                if (controllerLongPressOnly) {
                    // Don't call callback on release in controller-long-press mode.
                    d("dispatchGenericMotionEvent controllerLongPressOnly: release -> no callback")
                    controllerLongPressTriggeredSinceDown = false
                } else {
                    if (pendingBackDown) {
                        d("dispatchGenericMotionEvent non-controller: release with pending -> callback")
                        pendingBackDown = false
                        try {
                            onEmulatedBackEvent()
                        } catch (t: Throwable) {
                            Log.e(TAG, "dispatchGenericMotionEvent: onEmulatedBackEvent failed", t)
                        }
                    } else {
                        pendingBackDown = false
                    }
                }
                emittedAny = true
            }
            backLongPressEmitted = false
        } else if (
            backPressedNow && lastBackDown &&
                isKeyAllowed(KeyEvent.KEYCODE_BACK) &&
                !backLongPressEmitted
        ) {
            val elapsed = max(0L, event.eventTime - backKeyDownTime)
            if (elapsed >= longPressTimeoutMs) {
                if (!suppressed) {
                    markBackDownGenerated(BackChannel.MOTION, event.eventTime)
                    if (controllerLongPressOnly) {
                        if (!controllerLongPressTriggeredSinceDown) {
                            controllerLongPressTriggeredSinceDown = true
                            d("dispatchGenericMotionEvent controllerLongPressOnly: long-press elapsed=$elapsed -> callback")
                            try {
                                onEmulatedBackEvent()
                            } catch (t: Throwable) {
                                Log.e(TAG, "dispatchGenericMotionEvent: onEmulatedBackEvent failed", t)
                            }
                        }
                    }
                    emittedAny = true
                }
                backLongPressEmitted = true
            }
        }

        lastBackDown = backPressedNow
        return emittedAny
    }

    fun resetState() {
        d("resetState()")
        lastBackDown = false
        backKeyDownTime = 0L
        backLongPressEmitted = false
        pendingBackDown = false
        controllerLongPressTriggeredSinceDown = false
        keyPressActive = false
        keyPressControllerLongPressOnly = false
        for (i in lastBackDownEventTimeByChannel.indices) {
            lastBackDownEventTimeByChannel[i] = -1L
        }
    }

    companion object {
        private const val TAG = "BackNavigationEventsAdapter"
        private const val DEFAULT_LONG_PRESS_TIMEOUT_MS: Int = 500
    }
}

