package com.phlox.tvwebbrowser.singleton.shortcuts

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.phlox.tvwebbrowser.browser.BrowserCommands
import java.util.EnumMap

/**
 * Created by PDT on 06.08.2017.
 *
 * The keys of the [Shortcut]s and their presses. An action has up to [Shortcut.MAX_BINDINGS] keys and a key
 * belongs to one action at most. An action the user never set has its default keys, without those the user
 * gave other actions. The keys are kept in the prefs as a list (`<prefsKey>_keys`); older versions kept one
 * key in `<prefsKey>`, `_mod` and `_lp`, which is read while there is no list.
 */
class ShortcutMgr @VisibleForTesting internal constructor(private val prefs: SharedPreferences) {
    private val bindings = EnumMap<Shortcut, List<KeyBinding>>(Shortcut::class.java)
    private val uiHandler = Handler(Looper.getMainLooper())

    /** The key held now that belongs to a shortcut, its down kept to be passed on when no shortcut takes it */
    private class Press(val keyCode: Int, val short: Shortcut?, val long: Shortcut?, val down: KeyEvent) {
        var longPressDone = false
    }
    private var press: Press? = null

    init {
        load()
    }

    private fun load() {
        val stored = EnumMap<Shortcut, List<KeyBinding>>(Shortcut::class.java)
        for (shortcut in Shortcut.entries) {
            storedBindings(shortcut)?.let { stored[shortcut] = it }
        }
        //a key the user gave an action is not a default key of another one, and no key is in two actions
        val taken = HashSet<KeyBinding>()
        stored.values.forEach { taken += it }
        val seen = HashSet<KeyBinding>()
        for (shortcut in Shortcut.entries) {
            val keys = stored[shortcut] ?: shortcut.defaultBindings.filter { it !in taken }
            bindings[shortcut] = keys.filter { seen.add(it) }
        }
    }

    /** @return the keys the user set (maybe none), or null if the action has its default keys */
    private fun storedBindings(shortcut: Shortcut): List<KeyBinding>? {
        val list = prefs.getString(shortcut.prefsKey + KEYS_SUFFIX, null)
        if (list != null) {
            return list.split(',').mapNotNull { KeyBinding.decode(it) }.distinct().take(Shortcut.MAX_BINDINGS)
        }
        if (!prefs.contains(shortcut.prefsKey)) return null
        val legacy = KeyBinding(prefs.getInt(shortcut.prefsKey, 0), prefs.getInt(shortcut.prefsKey + "_mod", 0),
            prefs.getBoolean(shortcut.prefsKey + "_lp", false))
        return if (legacy.isSet) listOf(legacy) else emptyList()
    }

    fun bindingsOf(shortcut: Shortcut): List<KeyBinding> = bindings[shortcut] ?: emptyList()

    /** Whether the action has its default keys (none of them given to another action counts too) */
    fun hasDefaultBindings(shortcut: Shortcut): Boolean = storedBindings(shortcut) == null

    /** The action that has [binding], if any */
    fun shortcutOf(binding: KeyBinding): Shortcut? = bindings.entries.firstOrNull { binding in it.value }?.key

    /**
     * Sets the keys of [shortcut]; a cleared list is kept too, otherwise the default keys would come back
     * after a restart.
     */
    fun save(shortcut: Shortcut, keys: List<KeyBinding>) {
        val list = keys.filter { it.isSet }.distinct().takeLast(Shortcut.MAX_BINDINGS)
        prefs.edit()
            .putString(shortcut.prefsKey + KEYS_SUFFIX, list.joinToString(",") { it.encode() })
            .removeLegacy(shortcut)
            .apply()
        load()
    }

    /**
     * Adds [binding] to the keys of [shortcut] (the oldest one goes when it has [Shortcut.MAX_BINDINGS]);
     * the action that had it loses it.
     */
    fun add(shortcut: Shortcut, binding: KeyBinding) {
        val owner = shortcutOf(binding)
        if (owner == shortcut) return
        if (owner != null) {
            save(owner, bindingsOf(owner) - binding)
        }
        save(shortcut, bindingsOf(shortcut) + binding)
    }

    /** Gives [shortcut] its default keys again (those another action has stay there) */
    fun resetToDefault(shortcut: Shortcut) {
        prefs.edit().remove(shortcut.prefsKey + KEYS_SUFFIX).removeLegacy(shortcut).apply()
        load()
    }

    private fun SharedPreferences.Editor.removeLegacy(shortcut: Shortcut) =
        remove(shortcut.prefsKey).remove(shortcut.prefsKey + "_mod").remove(shortcut.prefsKey + "_lp")

    @UiThread
    fun process(shortcut: Shortcut, commands: BrowserCommands) {
        when (shortcut) {
            Shortcut.NAVIGATE_BACK -> commands.goBack()
            Shortcut.NAVIGATE_FORWARD -> commands.goForward()
            Shortcut.NAVIGATE_HOME -> commands.goHome()
            Shortcut.REFRESH_PAGE -> commands.reload()
            Shortcut.VOICE_SEARCH -> commands.startVoiceSearch()
            Shortcut.FIND_IN_PAGE -> commands.findInPage()
            Shortcut.PAGE_UP -> commands.scrollPage(up = true)
            Shortcut.PAGE_DOWN -> commands.scrollPage(up = false)
            Shortcut.NEW_TAB -> commands.newTab()
            Shortcut.CLOSE_TAB -> commands.closeTab()
            Shortcut.NEXT_TAB -> commands.switchTab(1)
            Shortcut.PREVIOUS_TAB -> commands.switchTab(-1)
            Shortcut.PLAY_PAUSE -> commands.togglePlayback()
            Shortcut.MEDIA_STOP -> commands.stopPlayback()
            Shortcut.MEDIA_REWIND -> commands.rewind()
            Shortcut.MEDIA_FAST_FORWARD -> commands.fastForward()
        }
    }

    /**
     * The action of a short ([longPress] false) or a long press of the key. While the user types ([typing]),
     * a key that types a character without Ctrl, Alt or Meta is left to the text.
     */
    private fun shortcutFor(event: KeyEvent, longPress: Boolean, typing: Boolean): Shortcut? {
        val keyCode = event.keyCode
        val types = typing && event.isPrintingKey
        return bindings.entries.firstOrNull { (_, keys) ->
            keys.any {
                it.longPress == longPress && it.matches(keyCode, event.modifiers) &&
                        !(types && it.modifiers and TEXT_SAFE_MODIFIERS == 0)
            }
        }?.key
    }

    private fun onKeyDown(event: KeyEvent, commands: BrowserCommands, typing: Boolean): Boolean {
        val press = press
        if (press != null && event.repeatCount > 0 && press.keyCode == event.keyCode) {
            //the key is held: its repeats are taken, a long press runs its action once
            if (event.isLongPress && press.long != null && !press.longPressDone) {
                press.longPressDone = true
                run(press.long, commands)
            }
            return true
        }
        this.press = null
        if (event.repeatCount > 0) return false
        val short = shortcutFor(event, longPress = false, typing = typing)
        val long = shortcutFor(event, longPress = true, typing = typing)
        if (short == null && long == null) return false
        this.press = Press(event.keyCode, short, long, KeyEvent(event))
        return true
    }

    /**
     * The short press runs its action when the key goes up; a key that has only a long press action and was
     * released early goes where it would have gone: its down is given to [passOn], its up is not taken.
     */
    private fun onKeyUp(event: KeyEvent, commands: BrowserCommands, passOn: (KeyEvent) -> Unit): Boolean {
        val press = press ?: return false
        //a modifier released before the key does not end the press
        if (press.keyCode != event.keyCode) return false
        this.press = null
        if (press.longPressDone) return true
        if (press.short != null) {
            run(press.short, commands)
            return true
        }
        passOn(press.down)
        return false
    }

    /** The action runs after the event, so a dialog or a view it opens does not get the rest of it */
    private fun run(shortcut: Shortcut, commands: BrowserCommands) {
        uiHandler.post { process(shortcut, commands) }
    }

    /**
     * @param typing whether a text field has the focus
     * @param passOn takes the down of a key that turned out not to be a shortcut (a long press action
     * released early), to give it to the views before its up
     * @return true if the event belongs to a shortcut (its command is run after the event)
     */
    fun handle(
        event: KeyEvent, commands: BrowserCommands, typing: Boolean = false, passOn: (KeyEvent) -> Unit = {}
    ): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onKeyDown(event, commands, typing)
            KeyEvent.ACTION_UP -> onKeyUp(event, commands, passOn)
            else -> false
        }
    }

    companion object {
        const val PREFS_SHORTCUTS = "shortcuts"
        private const val KEYS_SUFFIX = "_keys"
        /** With one of these a key that types a character is still a shortcut in a text field */
        private const val TEXT_SAFE_MODIFIERS = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON

        private var instance: ShortcutMgr? = null

        @Synchronized fun getInstance(context: Context): ShortcutMgr {
            if (instance == null) {
                instance = ShortcutMgr(context.applicationContext.getSharedPreferences(PREFS_SHORTCUTS, Context.MODE_PRIVATE))
            }
            return instance!!
        }
    }
}
