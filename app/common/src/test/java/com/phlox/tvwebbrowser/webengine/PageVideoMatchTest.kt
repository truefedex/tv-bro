package com.phlox.tvwebbrowser.webengine

import com.phlox.tvwebbrowser.webengine.PageVideo.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageVideoMatchTest {
    private val file = PageVideo("https://a.com/movie.mp4", Kind.FILE)
    private val hls = PageVideo("https://cdn.com/hls/master.m3u8", Kind.HLS)
    private val dash = PageVideo("https://cdn.com/dash/manifest.mpd", Kind.DASH)

    @Test
    fun theVideoAtTheAddressOfTheMedia() {
        assertEquals(file, PageVideoMatch.of(MediaSource("https://a.com/movie.mp4", false), listOf(hls, file)))
        assertNull(PageVideoMatch.of(MediaSource("https://a.com/other.mp4", false), listOf(hls, file)))
    }

    @Test
    fun mediaThePageStreamsPlaysTheOnlyStreamOfItsFrame() {
        assertEquals(hls, PageVideoMatch.of(MediaSource(null, true), listOf(file, hls)))
        //two streams: not known which
        assertNull(PageVideoMatch.of(MediaSource(null, true), listOf(hls, dash)))
        assertNull(PageVideoMatch.of(MediaSource(null, true), listOf(file)))
        assertNull(PageVideoMatch.of(null, listOf(hls)))
    }

    @Test
    fun theVideosOfAFrameHaveNoVariants() {
        val list = PageVideoList<String>()
        list.onFrameVideos("a", listOf(hls, PageVideo("https://cdn.com/hls/720p/index.m3u8", Kind.HLS)))
        list.onFrameVideos("b", listOf(file))
        assertEquals(listOf(hls), list.videosOf("a"))
        assertEquals(emptyList<PageVideo>(), list.videosOf("c"))
        assertEquals(hls, PageVideoMatch.of(MediaSource(null, true), list.videosOf("a")))
    }

    @Test
    fun mediaThePageStreamsPlaysTheMasterAfterAnAd() {
        //ashdi.vip: an ad file plays first, then hls.js loads the master and one of its qualities
        val master = PageVideo("https://ashdi.vip/v/hls/BqiXj3SRl+1Ckw0=/index.m3u8", Kind.HLS)
        val ad = PageVideo("https://v.ads.example/media/v7.mp4", Kind.FILE)
        val list = PageVideoList<String>()
        list.onFrameVideos("a", listOf(master, ad, PageVideo("https://ashdi.vip/v/hls/480/BqiXj3SRl+1Ckw0=/index.m3u8", Kind.HLS)))
        assertEquals(ad, PageVideoMatch.of(MediaSource(ad.url, false), list.videosOf("a")))
        assertEquals(master, PageVideoMatch.of(MediaSource(null, true), list.videosOf("a")))
    }
}
