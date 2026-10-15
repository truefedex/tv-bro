package com.phlox.tvwebbrowser.singleton.shortcuts

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.UiThread
import com.phlox.tvwebbrowser.browser.BrowserCommands

/**
 * Created by PDT on 06.08.2017.
 */

class ShortcutMgr private constructor(private val prefs: SharedPreferences) {
    private val shortcuts = ArrayList<Shortcut>()
    private var trackingShortcuts: List<Shortcut>? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    init {
        for (shortcut in Shortcut.entries) {
            shortcut.keyCode = prefs.getInt(shortcut.prefsKey, shortcut.keyCode)
            shortcut.modifiers = prefs.getInt(shortcut.prefsKey + "_mod", shortcut.modifiers)
            shortcut.longPressFlag = prefs.getBoolean(shortcut.prefsKey + "_lp", shortcut.longPressFlag)
            if (shortcut.keyCode != 0) {
                shortcuts.add(shortcut)
            }
        }
    }

    fun save(shortcut: Shortcut) {
        if (shortcut.keyCode == 0) {
            prefs.edit()
                    .remove(shortcut.prefsKey)
                    .remove(shortcut.prefsKey + "_mod")
                    .remove(shortcut.prefsKey + "_lp")
                    .apply()
            shortcuts.remove(shortcut)
            return
        }
        if (!shortcuts.contains(shortcut)) {
            shortcuts.add(shortcut)
        }
        prefs.edit()
                .putInt(shortcut.prefsKey, shortcut.keyCode)
                .putInt(shortcut.prefsKey + "_mod", shortcut.modifiers)
                .putBoolean(shortcut.prefsKey + "_lp", shortcut.longPressFlag)
                .apply()
    }

    fun findForId(id: Int): Shortcut {
        val shortcut = Shortcut.entries[id]
        for (s in shortcuts) {
            if (s == shortcut) {
                return s
            }
        }
        return shortcut
    }

    @UiThread
    fun process(shortcut: Shortcut, commands: BrowserCommands) {
        when (shortcut) {
            Shortcut.NAVIGATE_BACK -> commands.goBack()
            Shortcut.NAVIGATE_HOME -> commands.goHome()
            Shortcut.REFRESH_PAGE -> commands.reload()
            Shortcut.VOICE_SEARCH -> commands.startVoiceSearch()
            Shortcut.PLAY_PAUSE -> commands.togglePlayback()
            Shortcut.MEDIA_STOP -> commands.stopPlayback()
            Shortcut.MEDIA_REWIND -> commands.rewind()
            Shortcut.MEDIA_FAST_FORWARD -> commands.fastForward()
        }
    }

    private fun shortCutsForEvent(keyCode: Int, modifiers: Int): List<Shortcut> {
        val findings = ArrayList<Shortcut>()
        for (shortcut in shortcuts) {
            if (shortcut.keyCode == keyCode) {
                if (shortcut.modifiers == modifiers) {
                    findings.add(shortcut)
                }
            }
        }
        return findings
    }

    private fun onKeyDown(event: KeyEvent, commands: BrowserCommands): Boolean {
        val shortcuts = shortCutsForEvent(event.keyCode, event.modifiers)
        if (shortcuts.isEmpty()) return false
        trackingShortcuts = shortcuts
        if (event.repeatCount == 0) {
            event.startTracking()
        }
        if (event.isLongPress) {
            return onKeyLongPress(event, commands)
        }
        return true
    }

    private fun onKeyUp(event: KeyEvent, commands: BrowserCommands): Boolean {
        val trackingShortcuts = trackingShortcuts ?: return false
        for (shortcut in trackingShortcuts) {
            if (shortcut.longPressFlag || event.modifiers != shortcut.modifiers) {
                continue
            }
            uiHandler.post { process(shortcut, commands) }
            this.trackingShortcuts = null
            return true
        }
        return false
    }

    private fun onKeyLongPress(event: KeyEvent, commands: BrowserCommands): Boolean {
        val trackingShortcuts = trackingShortcuts ?: return false
        for (shortcut in trackingShortcuts) {
            if (!shortcut.longPressFlag || event.modifiers != shortcut.modifiers) {
                continue
            }
            uiHandler.post { process(shortcut, commands) }
            this.trackingShortcuts = null
            return true
        }
        return false
    }

    /**
     * @return true if the event belongs to a shortcut (its command is run after the event)
     */
    fun handle(event: KeyEvent, commands: BrowserCommands): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onKeyDown(event, commands)
            KeyEvent.ACTION_UP -> onKeyUp(event, commands)
            else -> false
        }
    }

    companion object {
        const val PREFS_SHORTCUTS = "shortcuts"

        private var instance: ShortcutMgr? = null

        @Synchronized fun getInstance(context: Context): ShortcutMgr {
            if (instance == null) {
                instance = ShortcutMgr(context.applicationContext.getSharedPreferences(PREFS_SHORTCUTS, Context.MODE_PRIVATE))
            }
            return instance!!
        }
    }
}
