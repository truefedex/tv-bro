package com.phlox.tvwebbrowser.service.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadServiceNeedTest {
    private val changes = ArrayList<Boolean>()
    private val need = DownloadServiceNeed { changes += it }

    @Test
    fun aStartedActivityWithoutDownloadsDoesNotBindTheService() {
        need.started = true
        assertTrue(changes.isEmpty())
    }

    @Test
    fun aDownloadInProgressKeepsTheServiceWhileTheActivityIsStarted() {
        need.started = true
        need.inProgress = true
        need.started = false
        need.started = true
        need.inProgress = false
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test
    fun aDownloadInProgressOfAStoppedActivityDoesNotBindIt() {
        need.inProgress = true
        assertTrue(changes.isEmpty())
        need.started = true
        assertEquals(listOf(true), changes)
    }

    @Test
    fun shownScreensKeepTheServiceUntilTheLastOneCloses() {
        need.started = true
        need.onScreenShown(true)
        need.onScreenShown(true)
        need.onScreenShown(false)
        assertEquals(listOf(true), changes)
        need.onScreenShown(false)
        assertEquals(listOf(true, false), changes)
        //a close without a show does not make the count negative
        need.onScreenShown(false)
        need.onScreenShown(true)
        assertEquals(listOf(true, false, true), changes)
    }

    @Test
    fun aScreenAndADownloadTogetherReleaseItOnlyWhenBothAreGone() {
        need.started = true
        need.onScreenShown(true)
        need.inProgress = true
        need.onScreenShown(false)
        assertEquals(listOf(true), changes)
        need.inProgress = false
        assertEquals(listOf(true, false), changes)
    }
}
