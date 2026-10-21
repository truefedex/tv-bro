package com.phlox.tvwebbrowser.activity.main.dialogs

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.singleton.shortcuts.KeyBinding
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.NavigationReservedShortcutKeyCodes

/**
 * Sets the key of a [shortcut]: the next key pressed is saved (held long, as a long press) and the dialog
 * closes. The keys of the browser navigation (the D-pad, OK, Back) still move the focus to Clear and Cancel,
 * so they can not be set. A dialog has its own window, so the keys do not reach the shortcuts of the activity
 * while it is open.
 */
class ShortcutKeyDialog(context: Context, private val shortcut: Shortcut) : AlertDialog(context) {
    private val shortcutMgr = ShortcutMgr.getInstance(context)
    /** A long press was seen: it is saved when the key goes up, so the up does not reach the activity */
    private var longPressBinding: KeyBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTitle(shortcut.titleResId)
        setMessage(context.getString(R.string.settings_press_key) + "\n\n" +
                context.getString(R.string.settings_key_now_s, shortcutMgr.bindingOf(shortcut).toDisplayString(context)))
        setButton(DialogInterface.BUTTON_NEUTRAL, context.getString(R.string.clear)) { _, _ ->
            shortcutMgr.save(shortcut, KeyBinding.NONE)
        }
        setButton(DialogInterface.BUTTON_NEGATIVE, context.getString(R.string.cancel)) { _, _ -> }
        super.onCreate(savedInstanceState)
    }

    private fun resolveKeyCode(event: KeyEvent): Int = if (event.keyCode != 0) event.keyCode else event.scanCode

    private fun isReserved(event: KeyEvent) =
        resolveKeyCode(event) in NavigationReservedShortcutKeyCodes.reservedForUserShortcuts

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isReserved(event)) return super.onKeyDown(keyCode, event)
        event.startTracking()
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isReserved(event)) return super.onKeyLongPress(keyCode, event)
        longPressBinding = KeyBinding(resolveKeyCode(event), event.modifiers, longPress = true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isReserved(event)) return super.onKeyUp(keyCode, event)
        val binding = longPressBinding ?: KeyBinding(resolveKeyCode(event), event.modifiers)
        longPressBinding = null
        shortcutMgr.save(shortcut, binding)
        dismiss()
        return true
    }
}
