package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.model.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class DownloadRowTextTest {
    private val text = DownloadRowText(RuntimeEnvironment.getApplication())
    private val now = at(18, 16, 0)

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance(TimeZone.getDefault()).run {
        clear()
        set(2026, Calendar.OCTOBER, day, hour, minute)
        timeInMillis
    }

    private fun item(status: Int, size: Long = 48_200_000, received: Long = 18_600_000, speed: Long = 0,
                     error: String? = null, time: Long = at(18, 15, 18), referer: String? = "https://www.github.com/x") =
        DownloadItem(id = 1, time = time, filename = "a.apk", filepath = "", url = "https://cdn.github.com/a.apk",
            size = size, bytesReceived = received, status = status, referer = referer, error = error,
            bytesPerSecond = speed)

    //sizes are formatted by the platform
    private val progress = "${text.size(18_600_000)} of ${text.size(48_200_000)}"

    @Test
    fun inProgress() {
        assertEquals("$progress · ${text.size(2_400_000)}/s · 12 s left",
            text.caption(item(Download.Status.IN_PROGRESS, speed = 2_400_000), withHost = false, withDay = false, now = now))
        assertEquals("$progress · ${text.size(10_000)}/s · 50 min left · github.com",
            text.caption(item(Download.Status.IN_PROGRESS, speed = 10_000), withHost = true, withDay = false, now = now))
        assertEquals("$progress · ${text.size(1000)}/s · 8 h 13 min left",
            text.caption(item(Download.Status.IN_PROGRESS, speed = 1000), withHost = false, withDay = false, now = now))
        //an unknown size, no speed yet
        assertEquals(text.size(18_600_000), text.caption(item(Download.Status.IN_PROGRESS, size = 0), false, false, now))
    }

    @Test
    fun pausedFailedAndCancelled() {
        assertEquals("Paused · $progress", text.caption(item(Download.Status.PAUSED), false, false, now))
        assertEquals("Paused: connection lost · $progress",
            text.caption(item(Download.Status.PAUSED, error = Download.Error.CONNECTION), false, false, now))
        assertEquals("Failed: connection lost · 3:18 PM · github.com",
            text.caption(item(Download.Status.FAILED, error = Download.Error.CONNECTION), true, false, now))
        assertEquals("Failed: HTTP 404 · 3:18 PM",
            text.caption(item(Download.Status.FAILED, error = "HTTP 404"), false, false, now))
        assertEquals("Cancelled · 3:18 PM", text.caption(item(Download.Status.CANCELLED), false, false, now))
    }

    @Test
    fun completeWithTheDay() {
        val done = item(Download.Status.DONE, size = 601_000, received = 601_000)
        val size = text.size(601_000)
        assertEquals("$size · 3:18 PM · github.com", text.caption(done, true, true, now))
        assertEquals("$size · Yesterday", text.caption(done.copy(time = at(17, 9)), false, true, now))
        assertEquals("$size · Oct 2, 2026", text.caption(done.copy(time = at(2, 9)), false, true, now))
    }

    @Test
    fun hostOfThePageElseOfTheLink() {
        assertEquals("github.com", DownloadRowText.hostOf(item(Download.Status.DONE)))
        assertEquals("cdn.github.com", DownloadRowText.hostOf(item(Download.Status.DONE, referer = null)))
        //a page without a host (a link opened in a new tab)
        assertEquals("cdn.github.com", DownloadRowText.hostOf(item(Download.Status.DONE, referer = "about:blank")))
        assertNull(DownloadRowText.hostOf(item(Download.Status.DONE, referer = null).copy(url = "blob:null/123")))
    }
}
