package com.phlox.tvwebbrowser.activity.main

import android.os.Looper
import com.phlox.tvwebbrowser.activity.main.PageVideoHintController.Companion.FADE_MS
import com.phlox.tvwebbrowser.activity.main.PageVideoHintController.Companion.SHOWN_MS
import com.phlox.tvwebbrowser.activity.main.PageVideoHintController.Companion.STEADY_MS
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class PageVideoHintControllerTest {
    private val calls = mutableListOf<String>()
    private var allowed = true
    private val hint = PageVideoHintController(object : PageVideoHintController.Host {
        override val hintsAllowed get() = allowed
        override fun showHint() { calls += "show" }
        override fun hideHint(fade: Boolean) { calls += if (fade) "fade" else "hide" }
        override fun openVideos(video: PageVideo) { calls += "open ${video.url}" }
    })

    private val video = PageVideo("https://cdn.example.com/episode.m3u8", PageVideo.Kind.HLS)

    private fun media(playing: Boolean = true, muted: Boolean = false, duration: Double? = 1300.0,
                      fullscreen: Boolean = false) =
        MediaState(playing = playing, position = 10.0, duration = duration, fullscreen = fullscreen, muted = muted, at = 0)

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** A page shown without anything over it, its found video playing with sound */
    private fun play(page: String = "1|https://example.com/episode", media: MediaState = media()) {
        hint.setPage(page)
        hint.setBlocked(false)
        hint.setMediaVideo(video)
        hint.setMedia(media)
    }

    @Test
    fun showsAfterTheVideoPlayedWithSoundForAWhileAndHidesByItself() {
        play()
        idle(STEADY_MS - 100)
        assertEquals(emptyList<String>(), calls)
        idle(100)
        assertEquals(listOf("show"), calls)
        assertTrue(hint.isShown)
        assertTrue(hint.backCallback.isEnabled)
        idle(SHOWN_MS)
        assertEquals(listOf("show", "fade"), calls)
        assertFalse(hint.isShown)
        //Back still goes to the plate while it fades out
        assertTrue(hint.backCallback.isEnabled)
        idle(FADE_MS)
        assertFalse(hint.backCallback.isEnabled)
    }

    @Test
    fun reportsOfThePositionDoNotDelayIt() {
        play()
        idle(1000)
        hint.setMedia(media())
        idle(1000)
        hint.setMedia(media())
        idle(STEADY_MS - 2000)
        assertEquals(listOf("show"), calls)
    }

    @Test
    fun aPauseStartsTheWaitAgain() {
        play()
        idle(2000)
        hint.setMedia(media(playing = false))
        hint.setMedia(media())
        idle(STEADY_MS - 100)
        assertEquals(emptyList<String>(), calls)
        idle(100)
        assertEquals(listOf("show"), calls)
    }

    @Test
    fun notForMutedShortOrUnknownVideos() {
        play(media = media(muted = true))
        idle(STEADY_MS * 2)
        hint.setMedia(media(duration = 30.0))
        idle(STEADY_MS * 2)
        hint.setMedia(media())
        hint.setMediaVideo(null)
        idle(STEADY_MS * 2)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun liveStreamsHaveNoDurationAndGetTheHint() {
        play(media = media(duration = null))
        idle(STEADY_MS)
        assertEquals(listOf("show"), calls)
    }

    @Test
    fun theDurationOfTheFoundVideoCountsWhenTheMediaHasNone() {
        hint.setPage("1|https://example.com/")
        hint.setBlocked(false)
        hint.setMediaVideo(video.copy(duration = 20.0))
        hint.setMedia(media(duration = null))
        idle(STEADY_MS * 2)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun notInFullscreenNorWhileSomethingCoversThePage() {
        play(media = media(fullscreen = true))
        idle(STEADY_MS * 2)
        hint.setMedia(media())
        hint.setBlocked(true)
        idle(STEADY_MS * 2)
        assertEquals(emptyList<String>(), calls)
        hint.setBlocked(false)
        idle(STEADY_MS)
        assertEquals(listOf("show"), calls)
    }

    @Test
    fun notWhenHintsAreOff() {
        allowed = false
        play()
        idle(STEADY_MS * 2)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun onceAPage() {
        play()
        idle(STEADY_MS + SHOWN_MS)
        assertEquals(listOf("show", "fade"), calls)
        hint.setMedia(media(playing = false))
        hint.setMedia(media())
        idle(STEADY_MS * 2)
        assertEquals(listOf("show", "fade"), calls)
        //another page with another video: the hint may show again
        play(page = "1|https://example.com/episode-2")
        hint.setMediaVideo(PageVideo("https://cdn.example.com/episode-2.m3u8", PageVideo.Kind.HLS))
        idle(STEADY_MS)
        assertEquals(listOf("show", "fade", "show"), calls)
    }

    @Test
    fun onceAVideoWhenThePageRewritesItsAddressWhileItPlays() {
        play()
        idle(STEADY_MS)
        play(page = "1|https://example.com/episode?t=3")
        idle(STEADY_MS * 2)
        assertEquals(listOf("show", "hide"), calls)
    }

    @Test
    fun hidingForAnotherReasonEndsBackAtOnce() {
        play()
        idle(STEADY_MS)
        hint.setBlocked(true)
        assertFalse(hint.backCallback.isEnabled)
    }

    @Test
    fun hidesAtOnceWhenThePageChangesOrIsCovered() {
        play()
        idle(STEADY_MS)
        hint.setPage("2|https://example.com/other")
        assertEquals(listOf("show", "hide"), calls)
        play(page = "3|https://example.com/third")
        hint.setMediaVideo(video.copy(url = "https://cdn.example.com/third.m3u8"))
        idle(STEADY_MS)
        hint.setBlocked(true)
        play(page = "4|https://example.com/fourth")
        hint.setMediaVideo(video.copy(url = "https://cdn.example.com/fourth.m3u8"))
        idle(STEADY_MS)
        hint.setMedia(media(fullscreen = true))
        assertEquals(listOf("show", "hide", "show", "hide", "show", "hide"), calls)
    }

    @Test
    fun aPausedVideoKeepsThePlate() {
        play()
        idle(STEADY_MS)
        hint.setMedia(media(playing = false))
        assertTrue(hint.isShown)
    }

    @Test
    fun backOnThePlateOpensTheVideos() {
        play()
        idle(STEADY_MS)
        hint.backCallback.handleOnBackPressed()
        assertEquals(listOf("show", "hide", "open ${video.url}"), calls)
        assertFalse(hint.isShown)
        assertFalse(hint.backCallback.isEnabled)
    }
}
