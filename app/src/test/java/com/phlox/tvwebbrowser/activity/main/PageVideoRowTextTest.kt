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
    fun thePlayerCaptionTellsTheHeightTheKindAndTheSiteOfThePage() {
        //the site of the tab, not the server of the stream
        assertEquals("TV Bro player · 2160p · HLS · kino.example",
            PageVideoRowText.playerCaption("TV Bro player", video("https://cdn.example.com/master.m3u8", PageVideo.Kind.HLS),
                2160, "kino.example"))
    }

    @Test
    fun anUnknownHeightAndNoSiteAreLeftOut() {
        assertEquals("TV Bro player · DASH",
            PageVideoRowText.playerCaption("TV Bro player", video("https://example.com/v.mpd", PageVideo.Kind.DASH), null, null))
        assertEquals("TV Bro player · DASH",
            PageVideoRowText.playerCaption("TV Bro player", video("https://example.com/v.mpd", PageVideo.Kind.DASH), null, ""))
    }

    @Test
    fun aFileWithoutAnExtensionHasNoKind() {
        assertEquals("TV Bro player · 720p · example.com",
            PageVideoRowText.playerCaption("TV Bro player", video("https://example.com/video", PageVideo.Kind.FILE), 720,
                "example.com"))
    }

    @Test
    fun aStreamToChooseTellsItsFileKindHeightDurationAndServer() {
        val stream = video("https://cdn.example/films/42/index.m3u8?t=1", PageVideo.Kind.HLS).copy(height = 1080, duration = 1352.0)
        assertEquals("index.m3u8", PageVideoRowText.fileName(stream))
        assertEquals("HLS · 1080p · 22:32 · cdn.example", PageVideoRowText.choiceCaption(stream))
        //what is not known is left out
        assertEquals("HLS · ads.example", PageVideoRowText.choiceCaption(video("https://ads.example/promo.m3u8", PageVideo.Kind.HLS)))
        assertEquals("cdn.example", PageVideoRowText.fileName(video("https://cdn.example", PageVideo.Kind.HLS)))
    }

    @Test
    fun streamsOfTheSameNameTellTheirFolder() {
        val film = video("https://cdn.example/film/index.m3u8", PageVideo.Kind.HLS)
        val promo = video("https://cdn.example/promo/index.m3u8", PageVideo.Kind.HLS)
        val master = video("https://cdn.example/master.m3u8", PageVideo.Kind.HLS)
        assertEquals(listOf("film/index.m3u8", "promo/index.m3u8", "master.m3u8"),
            PageVideoRowText.fileNames(listOf(film, promo, master)))
        assertEquals(listOf("index.m3u8", "master.m3u8"), PageVideoRowText.fileNames(listOf(film, master)))
    }

    @Test
    fun theSitePlayerCaptionTellsTheSite() {
        assertEquals("Site player · kino.example", PageVideoRowText.sitePlayerCaption("Site player", "kino.example"))
        assertEquals("Site player", PageVideoRowText.sitePlayerCaption("Site player", ""))
    }
}
