package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.model.Download
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadsTrackerTest {
    private fun row(id: Long, status: Int, bytesReceived: Long = 0, size: Long = 1000) = Download().apply {
        this.id = id
        this.status = status
        this.bytesReceived = bytesReceived
        this.size = size
        filename = "file$id.zip"
    }

    @Test
    fun speedComesFromTheBytesBetweenRows() {
        val tracker = DownloadsTracker()
        assertEquals(0L, tracker.track(listOf(row(1, Download.Status.IN_PROGRESS, 0)), 0).first[0].bytesPerSecond)
        assertEquals(2000L, tracker.track(listOf(row(1, Download.Status.IN_PROGRESS, 2000)), 1000).first[0].bytesPerSecond)
        //rows closer than the sample interval keep the speed
        assertEquals(2000L, tracker.track(listOf(row(1, Download.Status.IN_PROGRESS, 2100)), 1100).first[0].bytesPerSecond)
        //later ones are smoothed: 2000 * 0.7 + 1000 * 0.3
        assertEquals(1700L, tracker.track(listOf(row(1, Download.Status.IN_PROGRESS, 3000)), 2000).first[0].bytesPerSecond)
    }

    @Test
    fun finishedAndPausedDownloadsHaveNoSpeed() {
        val tracker = DownloadsTracker()
        tracker.track(listOf(row(1, Download.Status.IN_PROGRESS, 0)), 0)
        assertEquals(0L, tracker.track(listOf(row(1, Download.Status.PAUSED, 500)), 1000).first[0].bytesPerSecond)
        //resumed: a new sample is taken
        assertEquals(0L, tracker.track(listOf(row(1, Download.Status.IN_PROGRESS, 500)), 2000).first[0].bytesPerSecond)
        assertEquals(500L, tracker.track(listOf(row(1, Download.Status.IN_PROGRESS, 1000)), 3000).first[0].bytesPerSecond)
    }

    @Test
    fun downloadsThatEndGiveEvents() {
        val tracker = DownloadsTracker()
        //no events for the downloads found at the start
        val (_, first) = tracker.track(listOf(row(1, Download.Status.DONE), row(2, Download.Status.IN_PROGRESS),
            row(3, Download.Status.IN_PROGRESS), row(4, Download.Status.IN_PROGRESS)), 0)
        assertTrue(first.isEmpty())

        val (_, events) = tracker.track(listOf(row(1, Download.Status.DONE), row(2, Download.Status.DONE),
            row(3, Download.Status.FAILED), row(4, Download.Status.CANCELLED)), 1000)
        assertEquals(2, events.size)
        assertTrue(events[0] is DownloadEvent.Completed)
        assertEquals(2L, events[0].item.id)
        assertTrue(events[1] is DownloadEvent.Failed)
        assertEquals(3L, events[1].item.id)

        //the same state again gives nothing
        assertTrue(tracker.track(listOf(row(2, Download.Status.DONE)), 2000).second.isEmpty())
    }

    @Test
    fun itemsCopyTheRows() {
        val download = row(7, Download.Status.FAILED, 40).apply {
            referer = "https://site.com/page"
            error = Download.Error.CONNECTION
            mimeType = "application/zip"
            resumable = true
        }
        val item = DownloadsTracker().track(listOf(download), 0).first.single()
        assertEquals(DownloadItem(id = 7, time = 0, filename = "file7.zip", filepath = "", url = "", size = 1000,
            bytesReceived = 40, status = Download.Status.FAILED, mimeType = "application/zip",
            referer = "https://site.com/page", error = Download.Error.CONNECTION, resumable = true), item)
        assertEquals(item, DownloadItem.of(item.toDownload()))
    }

    @Test
    fun base64DataIsDecodedWithAnyMimeType() {
        val expected = "hello".toByteArray()
        assertArrayEquals(expected, decodeBase64Data("data:text/plain;charset=utf-8;base64,aGVsbG8="))
        assertArrayEquals(expected, decodeBase64Data("data:;base64,aGVsbG8="))
        assertArrayEquals(expected, decodeBase64Data("aGVsbG8="))
    }
}
