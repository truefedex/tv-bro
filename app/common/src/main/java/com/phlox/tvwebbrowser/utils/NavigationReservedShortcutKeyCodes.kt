package com.phlox.tvwebbrowser.utils

import android.view.KeyEvent

/**
 * Keys consumed by [DPADNavigationEventsAdapter] and [BackNavigationEventsAdapter].
 * User-configurable shortcuts must not use these codes.
 */
object NavigationReservedShortcutKeyCodes {
    val dpadNavigationKeys: Set<Int> = intArrayOf(
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_UP_LEFT,
        KeyEvent.KEYCODE_DPAD_UP_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
        KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A
    ).toHashSet()

    val backNavigationKeys: Set<Int> = intArrayOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_BUTTON_B
    ).toHashSet()

    val reservedForUserShortcuts: Set<Int> = dpadNavigationKeys + backNavigationKeys
}
