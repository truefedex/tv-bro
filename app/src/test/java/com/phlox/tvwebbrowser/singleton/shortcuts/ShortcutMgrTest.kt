package com.phlox.tvwebbrowser.singleton.shortcuts

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.browser.BrowserCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Key events to shortcut commands.
 */
@RunWith(RobolectricTestRunner::class)
class ShortcutMgrTest {
    private val prefs = AppContext.get().getSharedPreferences("test_shortcuts", Context.MODE_PRIVATE)
    private val commands = RecordingCommands()

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    private fun key(action: Int, keyCode: Int, meta: Int = 0, repeat: Int = 0, flags: Int = 0) =
        KeyEvent(0L, 0L, action, keyCode, repeat, meta, 0, 0, flags)

    /** Key down and up; @return whether the events were taken by shortcuts */
    private fun ShortcutMgr.press(keyCode: Int, meta: Int = 0): Boolean {
        val down = handle(key(KeyEvent.ACTION_DOWN, keyCode, meta), commands)
        val up = handle(key(KeyEvent.ACTION_UP, keyCode, meta), commands)
        shadowOf(Looper.getMainLooper()).idle()
        return down && up
    }

    @Test
    fun defaultKeyRunsItsCommand() {
        val mgr = ShortcutMgr(prefs)
        assertTrue(mgr.press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(listOf("togglePlayback"), commands.log)
    }

    @Test
    fun keyWithoutShortcutIsNotTaken() {
        val mgr = ShortcutMgr(prefs)
        assertFalse(mgr.press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(emptyList<String>(), commands.log)
    }

    @Test
    fun modifiersMustMatch() {
        val mgr = ShortcutMgr(prefs)
        mgr.save(Shortcut.NAVIGATE_HOME, KeyBinding(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON))
        assertFalse(mgr.press(KeyEvent.KEYCODE_H))
        assertTrue(mgr.press(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON))
        assertEquals(listOf("goHome"), commands.log)
    }

    @Test
    fun longPressShortcutRunsOnlyOnLongPress() {
        val mgr = ShortcutMgr(prefs)
        mgr.save(Shortcut.NAVIGATE_BACK, KeyBinding(KeyEvent.KEYCODE_PROG_RED, longPress = true))
        mgr.press(KeyEvent.KEYCODE_PROG_RED)
        assertEquals(emptyList<String>(), commands.log)

        mgr.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED), commands)
        assertTrue(mgr.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, repeat = 1,
            flags = KeyEvent.FLAG_LONG_PRESS), commands))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("goBack"), commands.log)
    }

    @Test
    fun bindingsSurviveRestart() {
        ShortcutMgr(prefs).save(Shortcut.REFRESH_PAGE, KeyBinding(KeyEvent.KEYCODE_F5))
        val restarted = ShortcutMgr(prefs)
        assertEquals(KeyBinding(KeyEvent.KEYCODE_F5), restarted.bindingOf(Shortcut.REFRESH_PAGE))
        assertTrue(restarted.press(KeyEvent.KEYCODE_F5))
        assertEquals(listOf("reload"), commands.log)
    }

    @Test
    fun clearedDefaultKeyStaysClearedAfterRestart() {
        ShortcutMgr(prefs).save(Shortcut.PLAY_PAUSE, KeyBinding.NONE)
        val restarted = ShortcutMgr(prefs)
        assertEquals(KeyBinding.NONE, restarted.bindingOf(Shortcut.PLAY_PAUSE))
        assertFalse(restarted.press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test
    fun managersDoNotShareBindings() {
        ShortcutMgr(prefs).save(Shortcut.VOICE_SEARCH, KeyBinding.NONE)
        prefs.edit().clear().commit()
        assertEquals(Shortcut.VOICE_SEARCH.defaultBinding, ShortcutMgr(prefs).bindingOf(Shortcut.VOICE_SEARCH))
    }

    private class RecordingCommands : BrowserCommands {
        val log = ArrayList<String>()
        override fun goBack() { log += "goBack" }
        override fun goForward() { log += "goForward" }
        override fun goHome() { log += "goHome" }
        override fun reload() { log += "reload" }
        override fun startVoiceSearch() { log += "startVoiceSearch" }
        override fun togglePlayback() { log += "togglePlayback" }
        override fun stopPlayback() { log += "stopPlayback" }
        override fun rewind() { log += "rewind" }
        override fun fastForward() { log += "fastForward" }
    }
}
