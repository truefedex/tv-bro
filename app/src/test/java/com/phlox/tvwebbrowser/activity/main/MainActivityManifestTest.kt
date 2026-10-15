package com.phlox.tvwebbrowser.activity.main

import android.content.ComponentName
import android.content.pm.ActivityInfo
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import org.junit.Assert.assertEquals
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

    @Test
    fun incognitoActivityHandlesTheSameChanges() {
        assertEquals(configChanges(MainActivity::class.java), configChanges(IncognitoModeMainActivity::class.java))
    }
}
