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
 */

class ShortcutMgr @VisibleForTesting internal constructor(private val prefs: SharedPreferences) {
    private val bindings = EnumMap<Shortcut, KeyBinding>(Shortcut::class.java)
    private var trackingShortcuts: List<Shortcut>? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    init {
        for (shortcut in Shortcut.entries) {
            val default = shortcut.defaultBinding
            bindings[shortcut] = KeyBinding(
                prefs.getInt(shortcut.prefsKey, default.keyCode),
                prefs.getInt(shortcut.prefsKey + "_mod", default.modifiers),
                prefs.getBoolean(shortcut.prefsKey + "_lp", default.longPress)
            )
        }
    }

    fun bindingOf(shortcut: Shortcut): KeyBinding = bindings[shortcut] ?: shortcut.defaultBinding

    fun save(shortcut: Shortcut, binding: KeyBinding) {
        bindings[shortcut] = binding
        //a cleared key is stored too, otherwise the default one would come back after a restart
        prefs.edit()
                .putInt(shortcut.prefsKey, binding.keyCode)
                .putInt(shortcut.prefsKey + "_mod", binding.modifiers)
                .putBoolean(shortcut.prefsKey + "_lp", binding.longPress)
                .apply()
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

    private fun shortCutsForEvent(keyCode: Int, modifiers: Int): List<Shortcut> =
        bindings.filter { (_, binding) -> binding.isSet && binding.keyCode == keyCode && binding.modifiers == modifiers }
            .keys.toList()

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
            val binding = bindingOf(shortcut)
            if (binding.longPress || event.modifiers != binding.modifiers) {
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
            val binding = bindingOf(shortcut)
            if (!binding.longPress || event.modifiers != binding.modifiers) {
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
