package com.phlox.tvwebbrowser.singleton.shortcuts

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.UiThread
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.Config

import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.webengine.WebEngine

/**
 * Created by PDT on 06.08.2017.
 */

class ShortcutMgr private constructor() {
    private val shortcuts = ArrayList<Shortcut>()
    private var trackingShortcuts: List<Shortcut>? = null
    private val prefs: SharedPreferences =
        TVBro.instance.getSharedPreferences(PREFS_SHORTCUTS, Context.MODE_PRIVATE)
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
    fun process(shortcut: Shortcut, mainActivity: MainActivity, webEngine: WebEngine?) {
        when (shortcut) {
            Shortcut.MENU -> {
                mainActivity.toggleMenu()
            }
            Shortcut.NAVIGATE_BACK -> {
                mainActivity.navigateBack()
            }
            Shortcut.NAVIGATE_HOME -> {
                mainActivity.navigate(Config.HOME_URL_ALIAS)
            }
            Shortcut.REFRESH_PAGE -> {
                mainActivity.refresh()
            }
            Shortcut.VOICE_SEARCH -> {
                mainActivity.initiateVoiceSearch()
            }
            Shortcut.PLAY_PAUSE -> {
                webEngine?.togglePlayback()
            }
            Shortcut.MEDIA_STOP -> {
                webEngine?.stopPlayback()
            }
            Shortcut.MEDIA_REWIND -> {
                webEngine?.rewind()
            }
            Shortcut.MEDIA_FAST_FORWARD -> {
                webEngine?.fastForward()
            }
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

    private fun onKeyDown(event: KeyEvent, mainActivity: MainActivity, tab: WebTabState?): Boolean {
        val shortcuts = shortCutsForEvent(event.keyCode, event.modifiers)
        if (shortcuts.isEmpty()) return false
        trackingShortcuts = shortcuts
        if (event.repeatCount == 0) {
            event.startTracking()
        }
        if (event.isLongPress) {
            return onKeyLongPress(event, mainActivity, tab)
        }
        return true
    }

    private fun onKeyUp(event: KeyEvent, mainActivity: MainActivity, tab: WebTabState?): Boolean {
        val trackingShortcuts = trackingShortcuts ?: return false
        for (shortcut in trackingShortcuts) {
            if (shortcut.longPressFlag || event.modifiers != shortcut.modifiers) {
                continue
            }
            uiHandler.post { process(shortcut, mainActivity, tab?.webEngine) }
            this.trackingShortcuts = null
            return true
        }
        return false
    }

    private fun onKeyLongPress(event: KeyEvent, mainActivity: MainActivity, tab: WebTabState?): Boolean {
        val trackingShortcuts = trackingShortcuts ?: return false
        for (shortcut in trackingShortcuts) {
            if (!shortcut.longPressFlag || event.modifiers != shortcut.modifiers) {
                continue
            }
            uiHandler.post { process(shortcut, mainActivity, tab?.webEngine) }
            this.trackingShortcuts = null
            return true
        }
        return false
    }

    fun handle(event: KeyEvent, mainActivity: MainActivity, value: WebTabState?): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onKeyDown(event, mainActivity, value)
            KeyEvent.ACTION_UP -> onKeyUp(event, mainActivity, value)
            else -> false
        }
    }

    fun tryHandleEmulatedSimpleKeyPress(keyCode: Int, mainActivity: MainActivity, tab: WebTabState?): Boolean {
        val shortcuts = shortCutsForEvent(keyCode, 0)
        if (shortcuts.isEmpty()) return false
        uiHandler.post { process(shortcuts.first(), mainActivity, tab?.webEngine) }
        return true
    }

    companion object {
        const val PREFS_SHORTCUTS = "shortcuts"

        private var instance: ShortcutMgr? = null

        @Synchronized fun getInstance(): ShortcutMgr {
            if (instance == null) {
                instance = ShortcutMgr()
            }
            return instance!!
        }
    }
}
