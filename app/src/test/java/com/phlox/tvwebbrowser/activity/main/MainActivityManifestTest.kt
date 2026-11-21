package com.phlox.tvwebbrowser.activity.main

import android.content.ComponentName
import android.content.pm.ActivityInfo
import com.phlox.tvwebbrowser.activity.PictureInPictureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainActivityManifestTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun configChanges(activity: Class<*>): Int =
        context.packageManager.getActivityInfo(ComponentName(context, activity), 0).configChanges

    /** The engine views live as long as their tabs: a recreated activity would lose them */
    @Test
    fun browserActivityIsNotRecreatedWhenTheScreenRotatesOrResizes() {
        val handled = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or
                ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
                ActivityInfo.CONFIG_UI_MODE or ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or ActivityInfo.CONFIG_NAVIGATION
        val changes = configChanges(MainActivity::class.java)
        assertTrue(changes and handled == handled)
    }

    /** Other apps start the browser through it, the launcher too: one instance, which shows the space wanted */
    @Test
    fun browserActivityIsTheOnlyOne() {
        val browser = info(MainActivity::class.java)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, browser.launchMode)
        assertTrue(browser.exported)
    }

    private fun info(activity: Class<*>): ActivityInfo =
        context.packageManager.getActivityInfo(ComponentName(context, activity), 0)

    /**
     * The picture-in-picture window of the built-in player: resized without being recreated, and in a task of its
     * own, out of the recents, so the browser stays usable behind it and closing it leaves the browser alone
     */
    @Test
    fun pictureInPictureWindowLivesNextToTheBrowser() {
        val browser = info(MainActivity::class.java)
        val window = info(PictureInPictureActivity::class.java)
        assertEquals(browser.processName, window.processName)
        assertEquals(browser.configChanges, window.configChanges)
        assertNotEquals(browser.taskAffinity, window.taskAffinity)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, window.launchMode)
        assertTrue(window.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }
}
