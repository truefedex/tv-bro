package com.phlox.tvwebbrowser.channels

import android.content.Context
import android.net.Uri
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/** The two doors other apps can knock on: the posters provider and the links to downloads */
@RunWith(RobolectricTestRunner::class)
class ChannelLinksTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun uri(path: String) = Uri.parse("content://${ChannelPostersProvider.authority(context)}/$path")

    @Test
    fun onlyPosterFilesCanBeAsked() {
        val file = ChannelPostersProvider.posterFile(context, uri("tabs/0123abcd.webp"))
        assertEquals(File(ChannelPosters.postersDir(context), "tabs/0123abcd.webp"), file)
        assertNull(ChannelPostersProvider.posterFile(context, uri("tabs/../../databases/app.db")))
        assertNull(ChannelPostersProvider.posterFile(context, uri("tabs/%2E%2E%2Fsecret.webp")))
        assertNull(ChannelPostersProvider.posterFile(context, uri("history/0123.webp")))
        assertNull(ChannelPostersProvider.posterFile(context, uri("tabs/0123.png")))
    }

    @Test
    fun onlyTheAppAndSystemAppsMayRead() {
        assertTrue(ChannelPostersProvider.isAllowedCaller(context, Process.myUid()))
        val pm = shadowOf(context.packageManager)
        val other = 12345
        pm.setPackagesForUid(other, "com.example.other")
        assertFalse(ChannelPostersProvider.isAllowedCaller(context, other))
        assertFalse(ChannelPostersProvider.isAllowedCaller(context, 54321))
    }

    @Test
    fun aPosterIsDrawnOnceAndUnusedOnesAreDeleted() {
        var draws = 0
        fun poster(key: String) = ChannelPosters.poster(context, ChannelKind.TABS, key) {
            draws++
            ChannelPosters.siteTile(context, "example.com", null)
        }
        val first = poster(ChannelPosters.key("a"))
        assertEquals(first, poster(ChannelPosters.key("a")))
        assertEquals(1, draws)
        val second = poster(ChannelPosters.key("b"))
        assertNotEquals(first, second)
        ChannelPosters.cleanup(context, ChannelKind.TABS, listOf(second))
        val dir = File(ChannelPosters.postersDir(context), "tabs")
        assertEquals(listOf(Uri.parse(second).lastPathSegment), dir.list()!!.toList())
    }

    @Test
    fun downloadLinksAreSigned() {
        val intent = ChannelLaunchActivity.openDownloadIntent(context, 7)
        val token = intent.getStringExtra("token")!!
        assertEquals(token, ChannelLaunchActivity.tokenOf(context, 7))
        assertNotEquals(token, ChannelLaunchActivity.tokenOf(context, 8))

        //a forged link is refused: nothing opens, the activity just finishes
        val forged = ChannelLaunchActivity.openDownloadIntent(context, 7).putExtra("token", "00")
        val activity = Robolectric.buildActivity(ChannelLaunchActivity::class.java, forged).create().get()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
