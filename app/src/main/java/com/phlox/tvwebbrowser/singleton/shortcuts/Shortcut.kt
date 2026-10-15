package com.phlox.tvwebbrowser.singleton.shortcuts

import android.content.Context
import android.view.KeyEvent

import com.phlox.tvwebbrowser.R

/**
 * Created by PDT on 06.08.2017.
 */

enum class Shortcut(val titleResId: Int, val prefsKey: String, val defaultBinding: KeyBinding) {
    NAVIGATE_BACK(R.string.navigate_back,  "shortcut_nav_back", KeyBinding.NONE),
    NAVIGATE_HOME(R.string.navigate_home,  "shortcut_nav_home", KeyBinding.NONE),
    REFRESH_PAGE(R.string.refresh_page,  "shortcut_refresh_page", KeyBinding(285)),//KEYCODE_REFRESH
    VOICE_SEARCH(R.string.voice_search,  "shortcut_voice_search", KeyBinding(KeyEvent.KEYCODE_SEARCH)),
    PLAY_PAUSE(R.string.play_pause,  "shortcut_play_pause", KeyBinding(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
    MEDIA_STOP(R.string.media_stop, "shortcut_media_stop", KeyBinding(KeyEvent.KEYCODE_MEDIA_STOP)),
    MEDIA_REWIND(R.string.media_rewind, "shortcut_media_rewind", KeyBinding(KeyEvent.KEYCODE_MEDIA_REWIND)),
    MEDIA_FAST_FORWARD(R.string.media_fast_forward, "shortcut_media_fast_forward", KeyBinding(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));
}

/**
 * The key (with modifiers, short or long press) that triggers a [Shortcut].
 */
data class KeyBinding(val keyCode: Int, val modifiers: Int = 0, val longPress: Boolean = false) {
    val isSet: Boolean get() = keyCode != 0

    fun toDisplayString(context: Context): String {
        if (!isSet) return context.getString(R.string.not_set)
        var allKeys = ""
        if (longPress) {
            allKeys += context.getString(R.string.long_press) + " "
        }
        if (modifiers != 0) {
            allKeys += modifiersToString(modifiers)
        }
        allKeys += KeyEvent.keyCodeToString(keyCode)
        return allKeys
    }

    companion object {
        val NONE = KeyBinding(0)

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
    }
}
