package com.phlox.tvwebbrowser.utils

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Turns every Back of the user into one call of [onEmulatedBackEvent]. A Back comes through three
 * channels:
 * - [dispatchKeyEvent]: the BACK, ESCAPE and B (game controllers) keys, back on release;
 * - [dispatchSystemBackNavigationEvent]: the system back callback (gestures, and also key presses
 *   the activity did not take: since Android 13 the release of the key then comes canceled);
 * - [dispatchGenericMotionEvent]: the right and back buttons of a mouse, back on release.
 *
 * One press often comes through more than one channel (a key the views did not handle reaches the
 * system callback too, the back button of a mouse also comes as a BACK key), so an event of another
 * channel within [SAME_PRESS_WINDOW_MS] of the last one taken is part of the same press and ignored.
 *
 * With [gameControllersLongPressBForBackNavigation] (the direct navigation mode, where a short B
 * goes to the page), keys of game controllers go back on a long press only; remotes and keyboards
 * still go back on a short one.
 */
class BackNavigationEventsAdapter(
    private val onEmulatedBackEvent: () -> Unit,
) {
    var gameControllersLongPressBForBackNavigation: Boolean = false

    private enum class Channel { SYSTEM, KEY, MOUSE }

    private var lastChannel: Channel? = null
    private var lastChannelTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { fireBack() }

    /** Between the DOWN that started a key press and its UP. */
    private var keyPressActive = false

    /** Whether the UP of the key press is a Back: not for a long-press-only press or a consumed one. */
    private var keyPressBackOnRelease = false

    private var mouseButtonDown = false

    /** Whether the release of the mouse button is a Back (false after [consumeCurrentPress]). */
    private var mouseBackOnRelease = false

    fun dispatchSystemBackNavigationEvent(): Boolean {
        if (!takeChannel(Channel.SYSTEM, SystemClock.uptimeMillis())) return false
        fireBack()
        return true
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in NavigationReservedShortcutKeyCodes.backNavigationKeys) return false
        if (!takeChannel(Channel.KEY, event.eventTime)) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && !keyPressActive) {
                // a DOWN within an active press is the fallback BACK of an unhandled B or ESCAPE
                keyPressActive = true
                val longPressOnly = gameControllersLongPressBForBackNavigation && isControllerKey(event)
                keyPressBackOnRelease = !longPressOnly
                handler.removeCallbacks(longPressRunnable)
                if (longPressOnly) {
                    val held = event.eventTime - event.downTime
                    handler.postDelayed(longPressRunnable, (LONG_PRESS_TIMEOUT_MS - held).coerceAtLeast(0L))
                }
            }
            KeyEvent.ACTION_UP -> {
                // a canceled release is still a Back (see the class comment)
                handler.removeCallbacks(longPressRunnable)
                if (keyPressActive && keyPressBackOnRelease) fireBack()
                keyPressActive = false
            }
        }
        return true
    }

    /**
     * Game controllers do not set the mouse buttons in the button state, so this channel needs no
     * long press. The cursor's own mouse events go to the page views directly, not through here.
     */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val down = (event.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_BACK)) != 0
        if (down == mouseButtonDown) return false
        mouseButtonDown = down
        if (!takeChannel(Channel.MOUSE, event.eventTime)) return false

        if (down) {
            mouseBackOnRelease = true
        } else if (mouseBackOnRelease) {
            mouseBackOnRelease = false
            fireBack()
        }
        return true
    }

    /**
     * The back press in progress was already handled elsewhere (for example the fullscreen video
     * view of WebView leaves fullscreen on BACK down), so its release must not be one more back.
     * Note that [KeyEvent.FLAG_CANCELED] of the release can not tell this: when the system back
     * callback handles Back (seen on Android 16), the release of every BACK key comes canceled.
     */
    fun consumeCurrentPress() {
        handler.removeCallbacks(longPressRunnable)
        keyPressBackOnRelease = false
        mouseBackOnRelease = false
    }

    /** False when another channel took the same press; else the press belongs to [channel] now. */
    private fun takeChannel(channel: Channel, eventTime: Long): Boolean {
        val last = lastChannel
        if (last != null && last != channel && eventTime - lastChannelTime in 0L..SAME_PRESS_WINDOW_MS) {
            return false
        }
        lastChannel = channel
        lastChannelTime = eventTime
        return true
    }

    private fun fireBack() {
        try {
            onEmulatedBackEvent()
        } catch (t: Throwable) {
            Log.e(TAG, "onEmulatedBackEvent failed", t)
        }
    }

    /**
     * B is a controller key whatever its source (some pads send it from a keyboard source); whole
     * sources are matched, since remotes are keyboards and the keyboard source shares a bit with gamepads.
     */
    private fun isControllerKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)

    companion object {
        private const val TAG = "BackNavigationEventsAdapter"
        private const val SAME_PRESS_WINDOW_MS = 1000L
        private val LONG_PRESS_TIMEOUT_MS = ViewConfiguration.getLongPressTimeout().toLong()
    }
}
