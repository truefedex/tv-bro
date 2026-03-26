package com.phlox.tvwebbrowser.singleton.shortcuts

import android.content.Context
import android.view.KeyEvent

import com.phlox.tvwebbrowser.R

/**
 * Created by PDT on 06.08.2017.
 */

enum class Shortcut private constructor(var titleResId: Int, var prefsKey: String,
          var keyCode: Int, var modifiers: Int = 0, var longPressFlag: Boolean = false) {
    NAVIGATE_BACK(R.string.navigate_back,  "shortcut_nav_back", 0),
    NAVIGATE_HOME(R.string.navigate_home,  "shortcut_nav_home", 0),
    REFRESH_PAGE(R.string.refresh_page,  "shortcut_refresh_page", 285),//KEYCODE_REFRESH
    VOICE_SEARCH(R.string.voice_search,  "shortcut_voice_search", KeyEvent.KEYCODE_SEARCH),
    PLAY_PAUSE(R.string.play_pause,  "shortcut_play_pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    MEDIA_STOP(R.string.media_stop, "shortcut_media_stop", KeyEvent.KEYCODE_MEDIA_STOP),
    MEDIA_REWIND(R.string.media_rewind, "shortcut_media_rewind", KeyEvent.KEYCODE_MEDIA_REWIND),
    MEDIA_FAST_FORWARD(R.string.media_fast_forward, "shortcut_media_fast_forward", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);

    companion object {
        private fun modifiersToString(modifiers: Int): String {
            var result = ""
            if (modifiers and KeyEvent.META_ALT_ON != 0) {
                result += "ALT+"
            }
            if (modifiers and KeyEvent.META_CTRL_ON != 0) {
                result += "CTRL+"
            }
            if (modifiers and KeyEvent.META_SHIFT_ON != 0) {
                result += "SHIFT+"
            }
            return result
        }

        fun shortcutKeysToString(shortcut: Shortcut, context: Context): String {
            var allKeys = ""
            if (shortcut.longPressFlag) {
                allKeys += context.getString(R.string.long_press) + " "
            }
            if (shortcut.modifiers != 0) {
                allKeys += modifiersToString(shortcut.modifiers)
            }
            allKeys += KeyEvent.keyCodeToString(shortcut.keyCode)
            return allKeys
        }
    }
}
