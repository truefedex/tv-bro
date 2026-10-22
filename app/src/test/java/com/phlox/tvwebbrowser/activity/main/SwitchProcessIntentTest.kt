package com.phlox.tvwebbrowser.activity.main

import android.content.Intent
import android.net.Uri
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SwitchProcessIntentTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun viewIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    private fun assertStartsNewTask(intent: Intent) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertTrue(intent.flags and flags == flags)
    }

    /** A link opened by another app while incognito is on opens in the incognito process */
    @Test
    fun linkGoesToTheIncognitoActivity() {
        val source = viewIntent("https://example.com/page?q=1")
            .putExtra("com.phlox.tvwebbrowser.EXTRA_OPEN_IN_SAME_TAB", true)

        val intent = MainActivity.switchProcessIntent(context, true, source, 1234)

        assertEquals(IncognitoModeMainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://example.com/page?q=1"), intent.data)
        assertTrue(intent.getBooleanExtra("com.phlox.tvwebbrowser.EXTRA_OPEN_IN_SAME_TAB", false))
        assertEquals(1234, intent.getIntExtra(MainActivity.KEY_PROCESS_ID_TO_KILL, -1))
        assertStartsNewTask(intent)
    }

    /** The sync link of a TV (an App Link with its secret in the fragment) keeps all of it */
    @Test
    fun syncLinkGoesToTheNormalActivity() {
        val url = "https://tvbro.phlox.dev/sync#v=1&a=192.168.1.2&p=8000&s=secret"

        val intent = MainActivity.switchProcessIntent(context, false, viewIntent(url), 1234)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(url, intent.dataString)
        assertStartsNewTask(intent)
    }

    @Test
    fun typeIsKeptWithTheData() {
        val source = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("https://example.com/video.mp4"), "video/mp4")

        val intent = MainActivity.switchProcessIntent(context, true, source, 1234)

        assertEquals(Uri.parse("https://example.com/video.mp4"), intent.data)
        assertEquals("video/mp4", intent.type)
    }

    /** An intent restored from the history may hold the pid of a process that is gone */
    @Test
    fun pidOfThisProcessWins() {
        val source = viewIntent("https://example.com").putExtra(MainActivity.KEY_PROCESS_ID_TO_KILL, 42)

        val intent = MainActivity.switchProcessIntent(context, true, source, 1234)

        assertEquals(1234, intent.getIntExtra(MainActivity.KEY_PROCESS_ID_TO_KILL, -1))
    }

    /** Toggling incognito does not open the link the activity was started with again */
    @Test
    fun toggleForwardsNothing() {
        val intent = MainActivity.switchProcessIntent(context, true, null, 1234)

        assertEquals(IncognitoModeMainActivity::class.java.name, intent.component?.className)
        assertNull(intent.action)
        assertNull(intent.data)
        assertEquals(1234, intent.getIntExtra(MainActivity.KEY_PROCESS_ID_TO_KILL, -1))
        assertStartsNewTask(intent)
    }
}
