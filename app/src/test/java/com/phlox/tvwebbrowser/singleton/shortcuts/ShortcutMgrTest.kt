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
 * Key events to shortcut commands, and the keys of the actions.
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

    /** Holds the key until its long press, then releases it */
    private fun ShortcutMgr.longPress(keyCode: Int) {
        handle(key(KeyEvent.ACTION_DOWN, keyCode), commands)
        handle(key(KeyEvent.ACTION_DOWN, keyCode, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS), commands)
        handle(key(KeyEvent.ACTION_UP, keyCode), commands)
        shadowOf(Looper.getMainLooper()).idle()
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
    fun actionHasSeveralKeys() {
        val mgr = ShortcutMgr(prefs)
        assertTrue(mgr.press(KeyEvent.KEYCODE_F5))
        assertTrue(mgr.press(KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON))
        assertTrue(mgr.press(KeyEvent.KEYCODE_TAB, KeyEvent.META_CTRL_ON))
        assertTrue(mgr.press(KeyEvent.KEYCODE_TAB, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
        assertTrue(mgr.press(KeyEvent.KEYCODE_FORWARD))
        assertTrue(mgr.press(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON))
        assertEquals(listOf("reload", "reload", "switchTab(1)", "switchTab(-1)", "goForward", "closeTab"), commands.log)
    }

    @Test
    fun channelKeysPageByDefault() {
        val mgr = ShortcutMgr(prefs)
        assertTrue(mgr.press(KeyEvent.KEYCODE_CHANNEL_UP))
        assertTrue(mgr.press(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(listOf("scrollPage(up)", "scrollPage(down)"), commands.log)
    }

    @Test
    fun directNavigationHasNoDefaultKeysButTakesOne() {
        val mgr = ShortcutMgr(prefs)
        assertEquals(emptyList<KeyBinding>(), mgr.bindingsOf(Shortcut.DIRECT_NAVIGATION))
        mgr.add(Shortcut.DIRECT_NAVIGATION, KeyBinding(KeyEvent.KEYCODE_PROG_BLUE))
        assertTrue(mgr.press(KeyEvent.KEYCODE_PROG_BLUE))
        assertEquals(listOf("toggleDirectNavigation"), commands.log)
    }

    @Test
    fun clearBrowsingDataHasNoDefaultKeysButTakesOne() {
        val mgr = ShortcutMgr(prefs)
        assertEquals(emptyList<KeyBinding>(), mgr.bindingsOf(Shortcut.CLEAR_BROWSING_DATA))
        mgr.add(Shortcut.CLEAR_BROWSING_DATA, KeyBinding(KeyEvent.KEYCODE_PROG_RED))
        assertTrue(mgr.press(KeyEvent.KEYCODE_PROG_RED))
        assertEquals(listOf("clearBrowsingData"), commands.log)
    }

    @Test
    fun defaultKeysAreNotInTwoActions() {
        val keys = Shortcut.entries.flatMap { it.defaultBindings.toList() }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(Shortcut.entries.all { it.defaultBindings.size <= Shortcut.MAX_BINDINGS })
    }

    @Test
    fun modifiersMustMatch() {
        val mgr = ShortcutMgr(prefs)
        mgr.save(Shortcut.NAVIGATE_HOME, listOf(KeyBinding(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)))
        assertFalse(mgr.press(KeyEvent.KEYCODE_H))
        assertFalse(mgr.press(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
        assertTrue(mgr.press(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON))
        assertEquals(listOf("goHome"), commands.log)
    }

    @Test
    fun sideOfModifierDoesNotMatter() {
        val mgr = ShortcutMgr(prefs)
        mgr.save(Shortcut.NAVIGATE_HOME,
            listOf(KeyBinding(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)))
        assertTrue(mgr.press(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON))
        //Num Lock is not a modifier of a binding
        assertTrue(mgr.press(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON or KeyEvent.META_NUM_LOCK_ON))
        assertEquals(listOf("goHome", "goHome"), commands.log)
    }

    @Test
    fun modifierReleasedFirstStillRunsShortcut() {
        val mgr = ShortcutMgr(prefs)
        assertTrue(mgr.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON), commands))
        assertFalse(mgr.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT), commands))
        assertTrue(mgr.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T), commands))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("newTab"), commands.log)
    }

    @Test
    fun longPressShortcutRunsOnlyOnLongPress() {
        val mgr = ShortcutMgr(prefs)
        mgr.save(Shortcut.NAVIGATE_BACK, listOf(KeyBinding(KeyEvent.KEYCODE_PROG_RED, longPress = true)))
        mgr.press(KeyEvent.KEYCODE_PROG_RED)
        assertEquals(emptyList<String>(), commands.log)

        mgr.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED), commands)
        assertTrue(mgr.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED, repeat = 1,
            flags = KeyEvent.FLAG_LONG_PRESS), commands))
        //the up of a long press is taken too, so the page does not get an up without its down
        assertTrue(mgr.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_RED), commands))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("goBack"), commands.log)
    }

    @Test
    fun shortPressOfLongPressKeyIsPassedOn() {
        val mgr = ShortcutMgr(prefs)
        mgr.save(Shortcut.NAVIGATE_BACK, listOf(KeyBinding(KeyEvent.KEYCODE_PROG_RED, longPress = true)))
        val passed = ArrayList<KeyEvent>()
        assertTrue(mgr.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_RED), commands, passOn = { passed += it }))
        assertFalse(mgr.handle(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_RED), commands, passOn = { passed += it }))
        assertEquals(1, passed.size)
        assertEquals(KeyEvent.ACTION_DOWN, passed[0].action)
        assertEquals(KeyEvent.KEYCODE_PROG_RED, passed[0].keyCode)
    }

    @Test
    fun shortAndLongPressOfOneKeyAreApart() {
        val mgr = ShortcutMgr(prefs)
        mgr.save(Shortcut.NAVIGATE_HOME, listOf(KeyBinding(KeyEvent.KEYCODE_PROG_RED)))
        mgr.save(Shortcut.NAVIGATE_BACK, listOf(KeyBinding(KeyEvent.KEYCODE_PROG_RED, longPress = true)))
        mgr.press(KeyEvent.KEYCODE_PROG_RED)
        mgr.longPress(KeyEvent.KEYCODE_PROG_RED)
        assertEquals(listOf("goHome", "goBack"), commands.log)
    }

    @Test
    fun addedKeyLeavesItsOldAction() {
        val mgr = ShortcutMgr(prefs)
        val f5 = KeyBinding(KeyEvent.KEYCODE_F5)
        mgr.add(Shortcut.NAVIGATE_HOME, f5)
        assertEquals(Shortcut.NAVIGATE_HOME, mgr.shortcutOf(f5))
        assertFalse(f5 in mgr.bindingsOf(Shortcut.REFRESH_PAGE))
        assertTrue(mgr.press(KeyEvent.KEYCODE_F5))
        assertEquals(listOf("goHome"), commands.log)
        //after a restart too
        assertEquals(Shortcut.NAVIGATE_HOME, ShortcutMgr(prefs).shortcutOf(f5))
    }

    @Test
    fun keyTheUserGaveIsNotDefaultOfAnother() {
        //an older version kept one key: Ctrl+T for Home, which is now a default key of New tab
        prefs.edit().putInt("shortcut_nav_home", KeyEvent.KEYCODE_T)
            .putInt("shortcut_nav_home_mod", KeyEvent.META_CTRL_ON).commit()
        val mgr = ShortcutMgr(prefs)
        assertEquals(listOf(KeyBinding(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON)), mgr.bindingsOf(Shortcut.NAVIGATE_HOME))
        assertEquals(emptyList<KeyBinding>(), mgr.bindingsOf(Shortcut.NEW_TAB))
        assertTrue(mgr.press(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON))
        assertEquals(listOf("goHome"), commands.log)
    }

    @Test
    fun oldestKeyGoesPastTheLimit() {
        val mgr = ShortcutMgr(prefs)
        mgr.save(Shortcut.NAVIGATE_HOME, emptyList())
        val keys = listOf(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4)
            .map { KeyBinding(it) }
        keys.forEach { mgr.add(Shortcut.NAVIGATE_HOME, it) }
        assertEquals(keys.drop(1), mgr.bindingsOf(Shortcut.NAVIGATE_HOME))
    }

    @Test
    fun resetGivesDefaultKeysBack() {
        val mgr = ShortcutMgr(prefs)
        mgr.save(Shortcut.REFRESH_PAGE, emptyList())
        assertFalse(mgr.hasDefaultBindings(Shortcut.REFRESH_PAGE))
        mgr.resetToDefault(Shortcut.REFRESH_PAGE)
        assertTrue(mgr.hasDefaultBindings(Shortcut.REFRESH_PAGE))
        assertEquals(Shortcut.REFRESH_PAGE.defaultBindings.toList(), mgr.bindingsOf(Shortcut.REFRESH_PAGE))
    }

    @Test
    fun typedCharacterIsLeftToTextField() {
        val mgr = ShortcutMgr(prefs)
        mgr.save(Shortcut.NAVIGATE_HOME, listOf(KeyBinding(KeyEvent.KEYCODE_H)))
        assertFalse(mgr.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H), commands, typing = true))
        //with Ctrl it is still a shortcut
        assertTrue(mgr.handle(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON), commands, typing = true))
    }

    @Test
    fun bindingsSurviveRestart() {
        ShortcutMgr(prefs).save(Shortcut.NAVIGATE_HOME, listOf(KeyBinding(KeyEvent.KEYCODE_F2), KeyBinding(KeyEvent.KEYCODE_F3)))
        val restarted = ShortcutMgr(prefs)
        assertEquals(listOf(KeyBinding(KeyEvent.KEYCODE_F2), KeyBinding(KeyEvent.KEYCODE_F3)),
            restarted.bindingsOf(Shortcut.NAVIGATE_HOME))
        assertTrue(restarted.press(KeyEvent.KEYCODE_F3))
        assertEquals(listOf("goHome"), commands.log)
    }

    @Test
    fun keyOfOlderVersionIsRead() {
        prefs.edit().putInt("shortcut_refresh_page", KeyEvent.KEYCODE_F9)
            .putBoolean("shortcut_refresh_page_lp", true).commit()
        assertEquals(listOf(KeyBinding(KeyEvent.KEYCODE_F9, longPress = true)),
            ShortcutMgr(prefs).bindingsOf(Shortcut.REFRESH_PAGE))
        //a key cleared in an older version stays cleared
        prefs.edit().clear().putInt("shortcut_play_pause", 0).commit()
        assertEquals(emptyList<KeyBinding>(), ShortcutMgr(prefs).bindingsOf(Shortcut.PLAY_PAUSE))
    }

    @Test
    fun clearedDefaultKeyStaysClearedAfterRestart() {
        ShortcutMgr(prefs).save(Shortcut.PLAY_PAUSE, emptyList())
        val restarted = ShortcutMgr(prefs)
        assertEquals(emptyList<KeyBinding>(), restarted.bindingsOf(Shortcut.PLAY_PAUSE))
        assertFalse(restarted.press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test
    fun managersDoNotShareBindings() {
        ShortcutMgr(prefs).save(Shortcut.VOICE_SEARCH, emptyList())
        prefs.edit().clear().commit()
        assertEquals(Shortcut.VOICE_SEARCH.defaultBindings.toList(), ShortcutMgr(prefs).bindingsOf(Shortcut.VOICE_SEARCH))
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
        override fun findInPage() { log += "findInPage" }
        override fun scrollPage(up: Boolean) { log += if (up) "scrollPage(up)" else "scrollPage(down)" }
        override fun toggleDirectNavigation() { log += "toggleDirectNavigation" }
        override fun newTab() { log += "newTab" }
        override fun closeTab() { log += "closeTab" }
        override fun switchTab(offset: Int) { log += "switchTab($offset)" }
        override fun clearBrowsingData() { log += "clearBrowsingData" }
    }
}
