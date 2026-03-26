package com.phlox.tvwebbrowser.activity.main.dialogs

import android.app.Dialog
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.NavigationReservedShortcutKeyCodes

/**
 * Created by PDT on 06.08.2017.
 */

class ShortcutDialog(context: Context, private val shortcut: Shortcut) : Dialog(context) {
    private val tvActionTitle: TextView
    private val tvActionKey: TextView
    private val btnSetKey: Button
    private val btnClearKey: Button
    private var keyListenMode = false

    init {
        setCancelable(true)
        setContentView(R.layout.dialog_shortcut)
        setTitle(R.string.shortcut)

        tvActionTitle = findViewById(R.id.tvActionTitle)
        tvActionKey = findViewById(R.id.tvActionKey)
        btnSetKey = findViewById(R.id.btnSetKey)
        btnClearKey = findViewById(R.id.btnClearKey)

        tvActionTitle.setText(shortcut.titleResId)
        updateShortcutNameDisplay()
        btnSetKey.setOnClickListener { toggleKeyListenState() }

        btnClearKey.setOnClickListener { clearKey() }
    }

    private fun clearKey() {
        if (keyListenMode) {
            toggleKeyListenState()
        }
        shortcut.keyCode = 0
        shortcut.modifiers = 0
        shortcut.longPressFlag = false
        ShortcutMgr.getInstance().save(shortcut)
        updateShortcutNameDisplay()
    }

    private fun updateShortcutNameDisplay() {
        tvActionKey.text = if (shortcut.keyCode == 0)
            context.getString(R.string.not_set)
        else {
            Shortcut.shortcutKeysToString(shortcut, context)
        }
    }

    private fun toggleKeyListenState() {
        keyListenMode = !keyListenMode
        btnSetKey.setText(if (keyListenMode) R.string.press_eny_key else R.string.set_key_for_action)
    }

    private fun resolveKeyCode(keyCode: Int, event: KeyEvent): Int =
        if (keyCode != 0) keyCode else event.scanCode

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!keyListenMode) {
            return super.onKeyDown(keyCode, event)
        }
        Log.d(TAG, "onKeyDown: keyCode = $keyCode, event = $event")
        event.startTracking()
        shortcut.longPressFlag = false
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!keyListenMode) {
            return super.onKeyUp(keyCode, event)
        }
        Log.d(TAG, "onKeyUp: keyCode = $keyCode, event = $event")
        val resolved = resolveKeyCode(keyCode, event)
        if (resolved in NavigationReservedShortcutKeyCodes.reservedForUserShortcuts) {
            Toast.makeText(context, R.string.shortcut_key_reserved_for_navigation, Toast.LENGTH_SHORT).show()
            toggleKeyListenState()
            return true
        }
        shortcut.keyCode = resolved
        shortcut.modifiers = event.modifiers
        ShortcutMgr.getInstance().save(shortcut)
        toggleKeyListenState()
        updateShortcutNameDisplay()
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!keyListenMode) {
            return super.onKeyLongPress(keyCode, event)
        }
        Log.d(TAG, "onKeyLongPress: keyCode = $keyCode, event = $event")
        val resolved = resolveKeyCode(keyCode, event)
        if (resolved in NavigationReservedShortcutKeyCodes.reservedForUserShortcuts) {
            Toast.makeText(context, R.string.shortcut_key_reserved_for_navigation, Toast.LENGTH_SHORT).show()
            toggleKeyListenState()
            return true
        }
        shortcut.keyCode = resolved
        shortcut.modifiers = event.modifiers
        shortcut.longPressFlag = true
        ShortcutMgr.getInstance().save(shortcut)
        toggleKeyListenState()
        updateShortcutNameDisplay()
        return true
    }

    companion object {
        val TAG: String = ShortcutDialog::class.java.simpleName
    }
}
