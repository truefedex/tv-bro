package com.phlox.tvwebbrowser.utils

import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.TextView

/**
 * TextView keeps the arrow keys of keyboards (the source exactly [InputDevice.SOURCE_KEYBOARD]) in a text field,
 * so that they never move the focus out of it: a keyboard has Tab for that. Remotes that the system takes for
 * keyboards (air mice) have no Tab, and with a keyboard on a TV the focus has to leave a field too, so [asDpad]
 * gives their arrows the source of a D-pad: they move the cursor in the text and leave the field at its ends,
 * as the D-pad of a remote does. Arrows with modifiers (Shift selects, Ctrl goes by words) stay as they are.
 */
object KeyboardArrows {
    fun asDpad(event: KeyEvent, focused: View?): KeyEvent {
        if (event.source != InputDevice.SOURCE_KEYBOARD || !isArrow(event.keyCode) || !event.hasNoModifiers()) return event
        //only the fields of the app: the arrows of a field of a page (WebView, GeckoView) stay those of a keyboard
        if (focused !is TextView || !focused.onCheckIsTextEditor()) return event
        return KeyEvent(event).apply { source = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD }
    }

    private fun isArrow(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
}
