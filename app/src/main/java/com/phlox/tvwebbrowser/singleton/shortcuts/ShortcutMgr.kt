package com.phlox.tvwebbrowser.singleton.shortcuts

import android.content.Context
import android.content.SharedPreferences
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.Config

import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx

import java.util.HashMap

/**
 * Created by PDT on 06.08.2017.
 */

class ShortcutMgr private constructor() {
    private val shortcuts: MutableMap<Int, Shortcut>
    private val prefs: SharedPreferences

    init {
        shortcuts = HashMap()
        prefs = TVBro.instance.getSharedPreferences(PREFS_SHORTCUTS, Context.MODE_PRIVATE)
        for (shortcut in Shortcut.values()) {
            shortcut.keyCode = prefs.getInt(shortcut.prefsKey, shortcut.keyCode)
            if (shortcut.keyCode != 0) {
                shortcuts[shortcut.keyCode] = shortcut
            }
        }
    }

    fun save(shortcut: Shortcut) {
        var oldKey = 0
        for ((key, value) in shortcuts) {
            if (value == shortcut) {
                oldKey = key
            }
        }
        if (oldKey != 0) {
            shortcuts.remove(oldKey)
        }
        if (shortcut.keyCode != 0) {
            shortcuts[shortcut.keyCode] = shortcut
        }
        prefs.edit()
                .putInt(shortcut.prefsKey, shortcut.keyCode)
                .apply()
    }

    fun findForId(id: Int): Shortcut? {
        for ((_, value) in shortcuts) {
            if (value.itemId == id) {
                return value
            }
        }
        return Shortcut.findForMenu(id)
    }

    fun process(keyCode: Int, mainActivity: MainActivity): Boolean {
        val shortcut = shortcuts[keyCode] ?: return false
        when (shortcut) {
            Shortcut.MENU -> {
                mainActivity.toggleMenu()
                return true
            }
            Shortcut.NAVIGATE_BACK -> {
                mainActivity.navigateBack()
                return true
            }
            Shortcut.NAVIGATE_HOME -> {
                mainActivity.navigate(prefs.getString(HOME_PAGE_KEY, Config.DEFAULT_HOME_URL)!!)
                return true
            }
            Shortcut.REFRESH_PAGE -> {
                mainActivity.refresh()
                return true
            }
            Shortcut.VOICE_SEARCH -> {
                mainActivity.initiateVoiceSearch()
                return true
            }
        }
    }

    fun canProcessKeyCode(keyCode: Int): Boolean {
        return shortcuts[keyCode] != null
    }

    companion object {
        val PREFS_SHORTCUTS = "shortcuts"
        const val HOME_PAGE_KEY = "home_page"
        private var instance: ShortcutMgr? = null

        @Synchronized fun getInstance(): ShortcutMgr {
            if (instance == null) {
                instance = ShortcutMgr()
            }
            return instance!!
        }
    }
}
