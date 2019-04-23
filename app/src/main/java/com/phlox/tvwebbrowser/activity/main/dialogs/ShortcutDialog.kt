package com.phlox.tvwebbrowser.activity.main.dialogs

import android.app.Dialog
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView

import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr

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
        ShortcutMgr.getInstance().save(shortcut)
        updateShortcutNameDisplay()
    }

    private fun updateShortcutNameDisplay() {
        tvActionKey.text = if (shortcut.keyCode == 0)
            context.getString(R.string.not_set)
        else
            KeyEvent.keyCodeToString(shortcut.keyCode)
    }

    private fun toggleKeyListenState() {
        keyListenMode = !keyListenMode
        btnSetKey.setText(if (keyListenMode) R.string.press_eny_key else R.string.set_key_for_action)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!keyListenMode) {
            return super.onKeyUp(keyCode, event)
        }
        shortcut.keyCode = if (keyCode != 0) keyCode else event.scanCode
        ShortcutMgr.getInstance().save(shortcut)
        toggleKeyListenState()
        updateShortcutNameDisplay()
        return true
    }
}
