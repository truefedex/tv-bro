package com.phlox.tvwebbrowser.activity.main

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.phlox.tvwebbrowser.webengine.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class VideoModeControllerTest {
    private val calls = mutableListOf<String>()
    private var osd: VideoModeController.Osd? = null
    private val video = VideoModeController(object : VideoModeController.Host {
        override fun hideCursor() { calls += "hideCursor" }
        override fun showCursor() { calls += "showCursor" }
        override fun togglePlayback() { calls += "toggle" }
        override fun seekTo(position: Double) { calls += "seekTo $position" }
        override fun seekBy(delta: Double) { calls += "seekBy $delta" }
        override fun renderOsd(osd: VideoModeController.Osd?) { this@VideoModeControllerTest.osd = osd }
    })

    private fun media(playing: Boolean = true, position: Double = 100.0, duration: Double? = 1000.0,
                      fullscreen: Boolean = true) =
        MediaState(playing, position, duration, fullscreen, at = SystemClock.elapsedRealtime())

    private fun key(action: Int, keyCode: Int, repeat: Int = 0, downTime: Long = SystemClock.uptimeMillis(),
                    eventTime: Long = downTime) = KeyEvent(downTime, eventTime, action, keyCode, repeat)

    private fun press(keyCode: Int): Boolean {
        val down = video.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, keyCode))
        val up = video.dispatchKeyEvent(key(KeyEvent.ACTION_UP, keyCode))
        assertEquals(down, up)
        return down
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun enter(media: MediaState = media()) {
        video.setFullscreen(true)
        video.setMedia(media)
    }

    @Test
    fun aFullscreenVideoTakesTheKeysAndHidesTheCursor() {
        enter()
        assertTrue(video.isActive)
        assertEquals(listOf("hideCursor"), calls)
        assertNotNull(osd)
    }

    @Test
    fun noVideoInTheFullscreenViewKeepsTheCursor() {
        enter(media(fullscreen = false))
        assertFalse(video.isActive)
        assertFalse(press(KeyEvent.KEYCODE_DPAD_LEFT))
        video.setMedia(null)
        assertFalse(video.isActive)
        assertNull(osd)
    }

    @Test
    fun okPlaysAndPauses() {
        enter()
        assertTrue(press(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("toggle", calls.last())
    }

    @Test
    fun aPressSeeksByAStep() {
        enter()
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("seekTo 110.0", calls.last())
        assertEquals(110.0, osd?.seekTarget)
        assertEquals(10.0, osd?.seekDelta)
        //a second press goes on from where the first one went, before the page reports it
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("seekTo 120.0", calls.last())
        assertTrue(press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("seekTo 110.0", calls.last())
    }

    @Test
    fun aSeekStaysInsideTheVideo() {
        enter(media(position = 3.0))
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("seekTo 0.0", calls.last())
    }

    @Test
    fun aHeldKeySeeksFasterAndGoesThereWhenItIsReleased() {
        enter()
        val downTime = SystemClock.uptimeMillis()
        video.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0, downTime))
        assertEquals("seekTo 110.0", calls.last())
        for (i in 1..30) {
            video.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, i, downTime, downTime + 500 + i * 50L))
        }
        //only the target moves while the key is held
        assertEquals("seekTo 110.0", calls.last())
        val target = 100.0 + VideoModeController.STEP + VideoModeController.heldDistance(2.0)
        assertEquals(target, osd!!.seekTarget!!, 0.001)
        video.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0, downTime, downTime + 2100))
        assertEquals("seekTo $target", calls.last())
    }

    @Test
    fun withoutADurationItSeeksByTheDistance() {
        enter(media(position = 0.0, duration = null))
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("seekBy -10.0", calls.last())
    }

    @Test
    fun upGivesTheCursorBackAndBackHidesItAgain() {
        enter()
        assertFalse(video.backCallback.isEnabled)
        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse(video.isActive)
        assertEquals("showCursor", calls.last())
        assertNull(osd)
        //the keys go to the cursor now
        assertFalse(press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(video.backCallback.isEnabled)
        video.backCallback.handleOnBackPressed()
        assertTrue(video.isActive)
        assertEquals("hideCursor", calls.last())
        assertFalse(video.backCallback.isEnabled)
    }

    @Test
    fun aHostCanTakeUpAndDown() {
        var upDowns = 0
        val player = VideoModeController(object : VideoModeController.Host {
            override fun hideCursor() {}
            override fun showCursor() { calls += "showCursor" }
            override fun togglePlayback() {}
            override fun seekTo(position: Double) {}
            override fun seekBy(delta: Double) {}
            override fun renderOsd(osd: VideoModeController.Osd?) {}
            override fun onUpDown(down: Boolean): Boolean {
                if (down) upDowns++
                return down
            }
        })
        player.setFullscreen(true)
        player.setMedia(media())
        assertTrue(press(player, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(1, upDowns)
        //the keys stay with the video, no cursor comes back
        assertTrue(player.isActive)
        assertFalse(player.backCallback.isEnabled)
        assertFalse(calls.contains("showCursor"))
        //Up the host does not take: the cursor comes back
        assertTrue(press(player, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(1, upDowns)
        assertFalse(player.isActive)
        assertTrue(calls.contains("showCursor"))
    }

    @Test
    fun theHintShowsInTheBar() {
        enter()
        idle(3500)
        assertNull(osd)
        video.hint = "Down: player"
        assertEquals("Down: player", osd?.hint)
        idle(VideoModeController.HINT_SHOWN_MS - 100)
        assertNotNull(osd)
        idle(200)
        assertNull(osd)
    }

    @Test
    fun aVideoWithAHintShowsTheBarLongerWhenItGoesFullscreen() {
        video.hint = "Down: player"
        assertNull(osd)
        enter()
        assertEquals("Down: player", osd?.hint)
        idle(VideoModeController.HINT_SHOWN_MS - 100)
        assertNotNull(osd)
        idle(200)
        assertNull(osd)
    }

    private fun press(controller: VideoModeController, keyCode: Int): Boolean {
        val down = controller.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, keyCode))
        val up = controller.dispatchKeyEvent(key(KeyEvent.ACTION_UP, keyCode))
        assertEquals(down, up)
        return down
    }

    @Test
    fun theUpOfAKeyThatLeftTheVideoModeDoesNotReachTheCursor() {
        enter()
        assertTrue(video.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
        assertFalse(video.isActive)
        assertTrue(video.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)))
    }

    @Test
    fun theBarHidesWhileItPlaysAndStaysWhilePaused() {
        enter()
        idle(3500)
        assertNull(osd)
        video.setMedia(media(playing = false))
        assertNotNull(osd)
        idle(10_000)
        assertNotNull(osd)
        video.setMedia(media(playing = true))
        idle(3500)
        assertNull(osd)
    }

    @Test
    fun leavingFullscreenEndsTheVideoMode() {
        enter()
        press(KeyEvent.KEYCODE_DPAD_UP)
        video.setFullscreen(false)
        assertFalse(video.isActive)
        assertFalse(video.backCallback.isEnabled)
        assertNull(osd)
        //a new fullscreen view starts in the video mode again
        video.setFullscreen(true)
        assertTrue(video.isActive)
    }
}
