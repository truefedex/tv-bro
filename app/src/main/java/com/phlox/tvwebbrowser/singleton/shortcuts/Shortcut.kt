package com.phlox.tvwebbrowser.singleton.shortcuts

import android.view.KeyEvent

import com.phlox.tvwebbrowser.R

/**
 * Created by PDT on 06.08.2017.
 */

enum class Shortcut private constructor(var titleResId: Int, var menuItemId: Int, var prefsKey: String, var keyCode: Int) {
    MENU(R.string.toggle_main_menu, R.id.miShortcutMenu, "shortcut_menu", KeyEvent.KEYCODE_BACK),
    NAVIGATE_BACK(R.string.navigate_back, R.id.miShortcutNavigateBack, "shortcut_nav_back", 0),
    NAVIGATE_HOME(R.string.navigate_home, R.id.miShortcutNavigateHome, "shortcut_nav_home", 0),
    REFRESH_PAGE(R.string.refresh_page, R.id.miShortcutRefreshPage, "shortcut_refresh_page", 0),
    VOICE_SEARCH(R.string.voice_search, R.id.miShortcutVoiceSearch, "shortcut_voice_search", KeyEvent.KEYCODE_SEARCH);


    companion object {

        fun findForMenu(menuId: Int): Shortcut? {
            for (shortcut in values()) {
                if (shortcut.menuItemId == menuId) {
                    return shortcut
                }
            }
            return null
        }
    }
}
