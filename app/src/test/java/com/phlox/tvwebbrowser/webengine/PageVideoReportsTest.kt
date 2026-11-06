package com.phlox.tvwebbrowser.webengine

import com.phlox.tvwebbrowser.webengine.PageVideo.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PageVideoReportsTest {
    @Test
    fun aManifestWithoutExtensionIsKnownByItsType() {
        val report = JSONObject("""{"videos": [
            {"url": "https://cdn.example.com/hls/abc", "element": false, "type": "application/vnd.apple.mpegurl"},
            {"url": "https://cdn.example.com/dash/abc", "element": false, "type": "application/dash+xml"},
            {"url": "https://cdn.example.com/other", "element": false},
            {"url": "https://cdn.example.com/a.m3u8", "element": false},
            {"url": "https://example.com/movie", "element": true, "width": 1280, "height": 720, "duration": 60.5}
        ], "title": "Film", "page": "https://example.com/watch", "ua": "UA"}""")
        assertEquals(listOf(
            PageVideo("https://cdn.example.com/hls/abc", Kind.HLS, "Film", "https://example.com/watch", "UA"),
            PageVideo("https://cdn.example.com/dash/abc", Kind.DASH, "Film", "https://example.com/watch", "UA"),
            PageVideo("https://cdn.example.com/a.m3u8", Kind.HLS, "Film", "https://example.com/watch", "UA"),
            PageVideo("https://example.com/movie", Kind.FILE, "Film", "https://example.com/watch", "UA", 1280, 720, 60.5),
        ), PageVideoReports.parse(report))
    }

    @Test
    fun theSubtitlesOfTheFrameGoWithEachOfItsVideos() {
        //fawesome.tv: an MSE player of an HLS stream without subtitles, and a <track> file it draws itself
        val report = JSONObject("""{"videos": [
            {"url": "https://cdn.example.com/film/master.m3u8", "element": false},
            {"url": "https://cdn.example.com/ad.mp4", "element": true}
        ], "subtitles": [
            {"url": "https://cdn.example.com/cc/en.vtt", "lang": "en", "label": "English", "showing": false},
            {"url": "https://cdn.example.com/cc/uk.vtt", "lang": null, "label": "", "showing": true},
            {"url": "https://cdn.example.com/cc/en.vtt", "lang": "en", "label": "English", "showing": false},
            {"url": "data:text/vtt,WEBVTT", "lang": "de"}
        ], "page": "https://example.com/watch"}""")
        val subtitles = listOf(PageSubtitle("https://cdn.example.com/cc/en.vtt", "en", "English"),
            PageSubtitle("https://cdn.example.com/cc/uk.vtt", showing = true))
        assertEquals(listOf(subtitles, subtitles), PageVideoReports.parse(report).map { it.subtitles })
    }

    @Test
    fun aReportWithoutSubtitlesGivesNone() {
        val report = JSONObject("""{"videos": [{"url": "https://cdn.example.com/a.m3u8", "element": false}]}""")
        assertEquals(emptyList<PageSubtitle>(), PageVideoReports.parse(report).single().subtitles)
    }
}
