package com.phlox.tvwebbrowser.webengine.webview

import com.phlox.tvwebbrowser.webengine.MediaState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaFramesTest {
    private val frames = MediaFrames<String>()

    private fun media(playing: Boolean, fullscreen: Boolean = false, position: Double = 0.0) =
        MediaState(playing = playing, position = position, duration = 100.0, fullscreen = fullscreen, at = 0)

    @Test
    fun noFramesNoTarget() {
        assertNull(frames.target())
        assertNull(frames.targetMedia())
    }

    @Test
    fun fullscreenFrameWins() {
        frames.onState("player", media(playing = false, fullscreen = true))
        frames.onState("ad", media(playing = true))
        assertEquals("player", frames.target())
        assertEquals(media(playing = false, fullscreen = true), frames.targetMedia())
    }

    @Test
    fun theFrameThatStartedPlayingLastWins() {
        frames.onState("a", media(playing = true))
        frames.onState("b", media(playing = true))
        //a report of the time keeps the moment it started playing
        frames.onState("a", media(playing = true, position = 5.0))
        assertEquals("b", frames.target())
    }

    @Test
    fun playingFrameWinsOverAPausedOne() {
        frames.onState("a", media(playing = true))
        frames.onState("b", media(playing = false))
        assertEquals("a", frames.target())
    }

    @Test
    fun withNothingPlayingTheLastChangedFrameWins() {
        frames.onState("a", media(playing = true))
        frames.onState("b", media(playing = false))
        frames.onState("a", media(playing = false))
        assertEquals("a", frames.target())
    }

    @Test
    fun framesWithoutMediaAndOldPagesAreForgotten() {
        frames.onState("a", media(playing = true))
        frames.onState("b", media(playing = false))
        frames.onState("a", null)
        assertEquals("b", frames.target())
        frames.clear()
        assertNull(frames.target())
    }

    @Test
    fun parsesTheReportsOfTheScript() {
        val state = WebViewMediaControls.parse(JSONObject(
            """{"media":true,"playing":true,"fullscreen":true,"position":12.5,"duration":600,"rate":1.5}"""), 42)
        assertEquals(MediaState(playing = true, position = 12.5, duration = 600.0, fullscreen = true, rate = 1.5, at = 42),
            state)
        //JSON.stringify makes an endless duration null
        assertNull(WebViewMediaControls.parse(JSONObject(
            """{"media":true,"playing":false,"fullscreen":false,"position":3,"duration":null,"rate":0}"""), 0)?.duration)
        assertNull(WebViewMediaControls.parse(JSONObject("""{"media":false}"""), 0))
    }
}
