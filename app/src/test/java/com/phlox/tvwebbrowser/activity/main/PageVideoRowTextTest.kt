package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.webengine.PageVideo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PageVideoRowTextTest {
    private fun video(url: String, kind: PageVideo.Kind) = PageVideo(url = url, kind = kind)

    @Test
    fun thePlayerCaptionTellsTheHeightTheKindAndTheServer() {
        assertEquals("Built-in player · 2160p · HLS · cdn.example.com",
            PageVideoRowText.playerCaption("Built-in player", video("https://cdn.example.com/master.m3u8", PageVideo.Kind.HLS), 2160))
    }

    @Test
    fun anUnknownHeightIsLeftOut() {
        assertEquals("Built-in player · DASH · example.com",
            PageVideoRowText.playerCaption("Built-in player", video("https://example.com/v.mpd", PageVideo.Kind.DASH), null))
    }

    @Test
    fun aFileWithoutAnExtensionHasNoKind() {
        assertEquals("Built-in player · 720p · example.com",
            PageVideoRowText.playerCaption("Built-in player", video("https://example.com/video", PageVideo.Kind.FILE), 720))
    }
}
