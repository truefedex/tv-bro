package com.phlox.tvwebbrowser.singleton.shortcuts

import android.content.Context
import android.view.KeyEvent

import com.phlox.tvwebbrowser.R

/**
 * Created by PDT on 06.08.2017.
 *
 * An action the user can give keys. [defaultBindings] are the keys of the remote and of a keyboard it has
 * until the user sets its keys.
 */
enum class Shortcut(val titleResId: Int, val prefsKey: String, vararg val defaultBindings: KeyBinding) {
    //no Alt+arrows: the D-pad keys are reserved for the navigation (NavigationReservedShortcutKeyCodes)
    NAVIGATE_BACK(R.string.navigate_back, "shortcut_nav_back"),
    NAVIGATE_FORWARD(R.string.navigate_forward, "shortcut_nav_forward", KeyBinding(KeyEvent.KEYCODE_FORWARD)),
    NAVIGATE_HOME(R.string.navigate_home, "shortcut_nav_home",
        KeyBinding(KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_ALT_ON)),
    REFRESH_PAGE(R.string.refresh_page, "shortcut_refresh_page",
        KeyBinding(KeyEvent.KEYCODE_REFRESH), KeyBinding(KeyEvent.KEYCODE_F5),
        KeyBinding(KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON)),
    VOICE_SEARCH(R.string.voice_search, "shortcut_voice_search", KeyBinding(KeyEvent.KEYCODE_SEARCH)),
    FIND_IN_PAGE(R.string.find_in_page, "shortcut_find_in_page", KeyBinding(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)),
    //the channel keys page in WebView by themselves (Chromium takes them for Page Up and Down), GeckoView ignores them
    PAGE_UP(R.string.page_up, "shortcut_page_up", KeyBinding(KeyEvent.KEYCODE_CHANNEL_UP)),
    PAGE_DOWN(R.string.page_down, "shortcut_page_down", KeyBinding(KeyEvent.KEYCODE_CHANNEL_DOWN)),
    //for remotes that give no long press of OK, which opens the cursor menu with the mode in it
    DIRECT_NAVIGATION(R.string.toggle_direct_navigation, "shortcut_direct_navigation"),
    NEW_TAB(R.string.new_tab, "shortcut_new_tab", KeyBinding(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)),
    CLOSE_TAB(R.string.close_tab, "shortcut_close_tab", KeyBinding(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON)),
    NEXT_TAB(R.string.next_tab, "shortcut_next_tab", KeyBinding(KeyEvent.KEYCODE_TAB, KeyEvent.META_CTRL_ON)),
    PREVIOUS_TAB(R.string.previous_tab, "shortcut_previous_tab",
        KeyBinding(KeyEvent.KEYCODE_TAB, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)),
    PLAY_PAUSE(R.string.play_pause, "shortcut_play_pause", KeyBinding(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
    MEDIA_STOP(R.string.media_stop, "shortcut_media_stop", KeyBinding(KeyEvent.KEYCODE_MEDIA_STOP)),
    MEDIA_REWIND(R.string.media_rewind, "shortcut_media_rewind", KeyBinding(KeyEvent.KEYCODE_MEDIA_REWIND)),
    MEDIA_FAST_FORWARD(R.string.media_fast_forward, "shortcut_media_fast_forward",
        KeyBinding(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)),
    CLEAR_BROWSING_DATA(R.string.clear_browsing_data, "shortcut_clear_browsing_data");

    companion object {
        /** The most keys an action keeps: a new one drops the oldest */
        const val MAX_BINDINGS = 3
    }
}

/**
 * The key (with modifiers, short or long press) that triggers a [Shortcut]. The modifiers are kept as
 * [normalizeModifiers] gives them, so the left and the right Ctrl are the same.
 */
class KeyBinding(val keyCode: Int, modifiers: Int = 0, val longPress: Boolean = false) {
    val modifiers: Int = normalizeModifiers(modifiers)

    val isSet: Boolean get() = keyCode != 0

    override fun equals(other: Any?): Boolean = other is KeyBinding &&
            other.keyCode == keyCode && other.modifiers == modifiers && other.longPress == longPress

    override fun hashCode(): Int = (keyCode * 31 + modifiers) * 31 + longPress.hashCode()

    override fun toString(): String = "KeyBinding($keyCode, $modifiers, $longPress)"

    /** Whether a key pressed with [eventModifiers] is this one (a short or a long press of it) */
    fun matches(keyCode: Int, eventModifiers: Int): Boolean =
        isSet && keyCode == this.keyCode && normalizeModifiers(eventModifiers) == modifiers

    /** The key and its press, as kept in the prefs: "keyCode:modifiers:longPress" */
    fun encode(): String = "$keyCode:$modifiers:${if (longPress) 1 else 0}"

    fun toDisplayString(context: Context): String {
        if (!isSet) return context.getString(R.string.not_set)
        var allKeys = ""
        if (longPress) {
            allKeys += context.getString(R.string.long_press) + " "
        }
        if (modifiers != 0) {
            allKeys += modifiersToString(modifiers)
        }
        allKeys += keyName(keyCode)
        return allKeys
    }

    companion object {
        val NONE = KeyBinding(0)

        /** The modifiers a binding tells apart: not the side of a key, the lock keys or Fn */
        private const val MODIFIERS_MASK = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or
                KeyEvent.META_SHIFT_ON or KeyEvent.META_META_ON

        fun normalizeModifiers(modifiers: Int): Int = KeyEvent.normalizeMetaState(modifiers) and MODIFIERS_MASK

        /** @return the binding [encode] gave, or null for a damaged one */
        fun decode(text: String): KeyBinding? {
            val parts = text.split(':')
            if (parts.size != 3) return null
            val keyCode = parts[0].toIntOrNull() ?: return null
            val modifiers = parts[1].toIntOrNull() ?: return null
            if (keyCode <= 0) return null
            return KeyBinding(keyCode, modifiers, parts[2] == "1")
        }

        /** The keys that only change other keys: they are never bound by themselves */
        fun isModifierKey(keyCode: Int): Boolean = KeyEvent.isModifierKey(keyCode) ||
                keyCode == KeyEvent.KEYCODE_FUNCTION || keyCode == KeyEvent.KEYCODE_CAPS_LOCK ||
                keyCode == KeyEvent.KEYCODE_NUM_LOCK || keyCode == KeyEvent.KEYCODE_SCROLL_LOCK

        /** Names of keys that read better than the ones made from their constants */
        private val KEY_NAMES = mapOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Play/Pause",
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to "Fast forward",
            KeyEvent.KEYCODE_PROG_RED to "Red",
            KeyEvent.KEYCODE_PROG_GREEN to "Green",
            KeyEvent.KEYCODE_PROG_YELLOW to "Yellow",
            KeyEvent.KEYCODE_PROG_BLUE to "Blue",
            KeyEvent.KEYCODE_DPAD_LEFT to "←",
            KeyEvent.KEYCODE_DPAD_RIGHT to "→",
            KeyEvent.KEYCODE_DPAD_UP to "↑",
            KeyEvent.KEYCODE_DPAD_DOWN to "↓",
            KeyEvent.KEYCODE_MOVE_HOME to "Home",
            KeyEvent.KEYCODE_MOVE_END to "End",
            KeyEvent.KEYCODE_CHANNEL_UP to "Channel +",
            KeyEvent.KEYCODE_CHANNEL_DOWN to "Channel −"
        )

        /**
         * The name of a key as on a remote: "Refresh" for KEYCODE_REFRESH, "Rewind" for KEYCODE_MEDIA_REWIND
         * (a key without a constant keeps its number)
         */
        fun keyName(keyCode: Int): String = KEY_NAMES[keyCode] ?: nameOfConstant(KeyEvent.keyCodeToString(keyCode))

        /** "Rewind" for "KEYCODE_MEDIA_REWIND"; a key without a constant keeps its number */
        fun nameOfConstant(constant: String): String {
            if (!constant.startsWith("KEYCODE_")) return constant
            val words = constant.removePrefix("KEYCODE_").removePrefix("MEDIA_").lowercase().replace('_', ' ')
            return words.replaceFirstChar { it.uppercase() }
        }

        private fun modifiersToString(modifiers: Int): String {
            var result = ""
            if (modifiers and KeyEvent.META_CTRL_ON != 0) {
                result += "Ctrl+"
            }
            if (modifiers and KeyEvent.META_ALT_ON != 0) {
                result += "Alt+"
            }
            if (modifiers and KeyEvent.META_SHIFT_ON != 0) {
                result += "Shift+"
            }
            if (modifiers and KeyEvent.META_META_ON != 0) {
                result += "Meta+"
            }
            return result
        }
    }
}

/** The keys of an action for a settings row: "F5, Ctrl+R", or Not set */
fun List<KeyBinding>.toDisplayString(context: Context): String =
    if (isEmpty()) context.getString(R.string.not_set) else joinToString(", ") { it.toDisplayString(context) }
