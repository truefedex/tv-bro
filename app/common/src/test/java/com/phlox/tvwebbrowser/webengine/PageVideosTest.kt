package com.phlox.tvwebbrowser.webengine

import com.phlox.tvwebbrowser.webengine.PageVideo.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageVideosTest {
    @Test
    fun manifestsAreStreamsWhoeverLoadsThem() {
        assertEquals(Kind.HLS, PageVideoUrls.kindOf("https://cdn.example.com/v/master.m3u8?token=1", false))
        assertEquals(Kind.HLS, PageVideoUrls.kindOf("http://cdn.example.com/LIVE/INDEX.M3U8", true))
        assertEquals(Kind.DASH, PageVideoUrls.kindOf("https://cdn.example.com/manifest.mpd#t=10", false))
    }

    @Test
    fun filesCountOnlyFromMediaElements() {
        assertEquals(Kind.FILE, PageVideoUrls.kindOf("https://example.com/movie.mp4", true))
        assertEquals(Kind.FILE, PageVideoUrls.kindOf("https://example.com/stream?id=42", true))
        //a segment of a stream the page fetches
        assertNull(PageVideoUrls.kindOf("https://cdn.example.com/seg-00012.mp4", false))
        assertNull(PageVideoUrls.kindOf("https://cdn.example.com/seg-00012.ts", false))
    }

    @Test
    fun onlyHttpAddressesWithAHost() {
        assertNull(PageVideoUrls.kindOf("blob:https://example.com/8c1e", true))
        assertNull(PageVideoUrls.kindOf("data:video/mp4;base64,AAAA", true))
        assertNull(PageVideoUrls.kindOf("file:///sdcard/a.m3u8", false))
        assertNull(PageVideoUrls.kindOf("https:///a.m3u8", false))
        //the query is not the path
        assertNull(PageVideoUrls.kindOf("https://example.com/play?file=a.m3u8", false))
    }

    @Test
    fun mimeTypes() {
        assertEquals("application/x-mpegURL", PageVideo("https://a.com/x.m3u8", Kind.HLS).mimeType)
        assertEquals("application/dash+xml", PageVideo("https://a.com/x.mpd", Kind.DASH).mimeType)
        assertEquals("video/webm", PageVideo("https://a.com/x.webm?x=1", Kind.FILE).mimeType)
        assertEquals("video/*", PageVideo("https://a.com/watch?id=1", Kind.FILE).mimeType)
    }

    @Test
    fun theListKeepsTheOrderAndMergesTheFrames() {
        val list = PageVideoList<String>()
        list.onFrameVideos("top", listOf(PageVideo("https://a.com/1.mp4", Kind.FILE, title = "Page")))
        list.onFrameVideos("player", listOf(
            PageVideo("https://cdn.com/2.m3u8", Kind.HLS),
            PageVideo("https://a.com/1.mp4", Kind.FILE, width = 1920, height = 1080)))
        val videos = list.videos()
        assertEquals(listOf("https://a.com/1.mp4", "https://cdn.com/2.m3u8"), videos.map { it.url })
        assertEquals("Page", videos[0].title)
        assertEquals(1920, videos[0].width)
    }

    @Test
    fun aFrameReportReplacesItsVideosAndTheOrderStays() {
        val list = PageVideoList<String>()
        list.onFrameVideos("a", listOf(PageVideo("https://a.com/1.m3u8", Kind.HLS)))
        list.onFrameVideos("b", listOf(PageVideo("https://b.com/2.m3u8", Kind.HLS)))
        list.onFrameVideos("a", emptyList())
        assertEquals(listOf("https://b.com/2.m3u8"), list.videos().map { it.url })
        list.onFrameVideos("a", listOf(PageVideo("https://a.com/1.m3u8", Kind.HLS)))
        assertEquals(listOf("https://a.com/1.m3u8", "https://b.com/2.m3u8"), list.videos().map { it.url })
        list.clear()
        assertEquals(emptyList<PageVideo>(), list.videos())
    }

    @Test
    fun theVariantsOfAnHlsStreamAreOneVideo() {
        val list = PageVideoList<String>()
        list.onFrameVideos("a", listOf(
            PageVideo("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", Kind.HLS),
            PageVideo("https://test-streams.mux.dev/x36xhzz/url_0/193039199_mp4_h264_aac_hd_7.m3u8", Kind.HLS),
            PageVideo("https://test-streams.mux.dev/x36xhzz/url_2/193039199_mp4_h264_aac_ld_7.m3u8", Kind.HLS),
            //another stream: another folder, another server
            PageVideo("https://test-streams.mux.dev/other/index.m3u8", Kind.HLS),
            PageVideo("https://cdn.example.com/x36xhzz/url_0/a.m3u8", Kind.HLS)))
        assertEquals(listOf("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "https://test-streams.mux.dev/other/index.m3u8",
            "https://cdn.example.com/x36xhzz/url_0/a.m3u8"), list.videos().map { it.url })
        assertTrue(PageVideoUrls.isVariantOf("https://cdn.com/hls/720p/index.m3u8?t=1", "https://cdn.com/hls/master.m3u8"))
        assertTrue(PageVideoUrls.isVariantOf("https://cdn.com/hls/chunklist_w1.m3u8", "https://cdn.com/hls/playlist.m3u8"))
        assertFalse(PageVideoUrls.isVariantOf("https://cdn.com/hls/master.m3u8", "https://cdn.com/hls/master.m3u8"))
        assertFalse(PageVideoUrls.isVariantOf("https://cdn.com/a/index.m3u8", "https://cdn.com/b/index.m3u8"))
    }

    @Test
    fun aVariantMayHaveAFolderPutIntoThePathOfItsMaster() {
        //ashdi.vip: the qualities are hls/1080/<token>/, hls/720/<token>/, hls/480/<token>/
        val base = "https://ashdi.vip/video28/1/serials/futurama_s1/s01e01_space_pilot_3000_amznwebdl1080p_67622/hls"
        val master = "$base/BqiXj3SRl+1Ckw0=/index.m3u8"
        assertTrue(PageVideoUrls.isVariantOf("$base/480/BqiXj3SRl+1Ckw0=/index.m3u8", master))
        assertTrue(PageVideoUrls.isVariantOf("$base/1080/BqiXj3SRl+1Ckw0=/index.m3u8", master))
        val list = PageVideoList<String>()
        list.onFrameVideos("a", listOf(PageVideo(master, Kind.HLS), PageVideo("$base/480/BqiXj3SRl+1Ckw0=/index.m3u8", Kind.HLS)))
        assertEquals(listOf(master), list.videos().map { it.url })
        //another episode on the same server, or a folder put in on another server
        assertFalse(PageVideoUrls.isVariantOf(
            "https://ashdi.vip/video28/1/serials/futurama_s1/s01e02_series_67623/hls/Xyz=/index.m3u8", master))
        assertFalse(PageVideoUrls.isVariantOf(
            "https://cdn.ashdi.vip/video28/1/serials/futurama_s1/s01e01_space_pilot_3000_amznwebdl1080p_67622/hls/480/BqiXj3SRl+1Ckw0=/index.m3u8",
            master))
        //a folder of the master replaced, not one put in
        assertFalse(PageVideoUrls.isVariantOf("https://cdn.com/hls/720/index.m3u8", "https://cdn.com/hls/master/index.m3u8"))
    }

    @Test
    fun theListHasALimit() {
        val list = PageVideoList<String>(limit = 2)
        list.onFrameVideos("a", (1..5).map { PageVideo("https://a.com/$it/index.m3u8", Kind.HLS) })
        assertEquals(listOf("https://a.com/1/index.m3u8", "https://a.com/2/index.m3u8"), list.videos().map { it.url })
    }
}
