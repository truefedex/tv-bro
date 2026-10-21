package com.phlox.tvwebbrowser.webengine.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFramesTest {
    private val frames = MediaFrames<String>()

    @Test
    fun noFramesNoTarget() {
        assertNull(frames.target())
    }

    @Test
    fun fullscreenFrameWins() {
        frames.onState("player", media = true, playing = false, fullscreen = true)
        frames.onState("ad", media = true, playing = true, fullscreen = false)
        assertEquals("player", frames.target())
    }

    @Test
    fun theFrameThatStartedPlayingLastWins() {
        frames.onState("a", media = true, playing = true, fullscreen = false)
        frames.onState("b", media = true, playing = true, fullscreen = false)
        //a report of the same state keeps the time it started playing
        frames.onState("a", media = true, playing = true, fullscreen = false)
        assertEquals("b", frames.target())
    }

    @Test
    fun playingFrameWinsOverAPausedOne() {
        frames.onState("a", media = true, playing = true, fullscreen = false)
        frames.onState("b", media = true, playing = false, fullscreen = false)
        assertEquals("a", frames.target())
    }

    @Test
    fun withNothingPlayingTheLastChangedFrameWins() {
        frames.onState("a", media = true, playing = true, fullscreen = false)
        frames.onState("b", media = true, playing = false, fullscreen = false)
        frames.onState("a", media = true, playing = false, fullscreen = false)
        assertEquals("a", frames.target())
    }

    @Test
    fun framesWithoutMediaAndOldPagesAreForgotten() {
        frames.onState("a", media = true, playing = true, fullscreen = false)
        frames.onState("b", media = true, playing = false, fullscreen = false)
        frames.onState("a", media = false, playing = false, fullscreen = false)
        assertEquals("b", frames.target())
        frames.clear()
        assertNull(frames.target())
    }
}
