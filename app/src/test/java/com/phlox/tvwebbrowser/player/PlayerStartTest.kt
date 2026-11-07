package com.phlox.tvwebbrowser.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartTest {
    private var time = 50_000L
    private val start = PlayerStart { time }

    @Test
    fun aVideoThatShowsAFrameInTimeStarted() {
        start.open(watch = true)
        time += 3000
        assertFalse(start.check(firstFrame = false, error = false))
        assertEquals(2000L, start.remaining())
        assertFalse(start.check(firstFrame = true, error = false))
        //an error later shows in the player
        time += 10_000
        assertFalse(start.check(firstFrame = true, error = true))
        assertNull(start.remaining())
    }

    @Test
    fun noFrameWithinFiveSecondsGivesTheVideoBackOnce() {
        start.open(watch = true)
        time += PlayerStart.TIMEOUT_MS
        assertTrue(start.check(firstFrame = false, error = false))
        assertFalse(start.check(firstFrame = false, error = false))
    }

    @Test
    fun anErrorBeforeTheFirstFrameGivesTheVideoBack() {
        start.open(watch = true)
        time += 300
        assertTrue(start.check(firstFrame = false, error = true))
    }

    @Test
    fun aVideoTheUserOpenedIsNotWatched() {
        start.open(watch = false)
        time += 60_000
        assertFalse(start.check(firstFrame = false, error = true))
        assertNull(start.remaining())
    }

    @Test
    fun aClosedPlayerIsNotWatched() {
        start.open(watch = true)
        start.stop()
        time += 60_000
        assertFalse(start.check(firstFrame = false, error = false))
    }
}
