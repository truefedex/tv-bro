package com.phlox.tvwebbrowser.webengine

import com.phlox.tvwebbrowser.webengine.PageVideo.Kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsPlaylistsTest {
    @Test
    fun aMasterListsItsPlaylistsByAbsoluteAddresses() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="UA",URI="audio/ua.m3u8"
            #EXT-X-STREAM-INF:RESOLUTION=1920x1032,BANDWIDTH=2128000
            https://other.cdn/video1/hls/1080/t/index.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=1280x688,BANDWIDTH=1096000,AUDIO="aud"

            720/index.m3u8?token=1
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=86000,URI="/iframes.m3u8"
        """.trimIndent()
        assertEquals(HlsPlaylist.Master(setOf(
            "https://cdn.com/hls/audio/ua.m3u8", "https://other.cdn/video1/hls/1080/t/index.m3u8",
            "https://cdn.com/hls/720/index.m3u8?token=1", "https://cdn.com/iframes.m3u8")),
            HlsPlaylist.parse(master, "https://cdn.com/hls/master.m3u8?t=5"))
    }

    @Test
    fun aMediaPlaylistTellsHowLongItLasts() {
        val vod = "#EXTM3U\r\n#EXT-X-TARGETDURATION:6\r\n#EXTINF:6.0,\r\na.ts\r\n#EXTINF:5.5,title\r\nb.ts\r\n#EXT-X-ENDLIST\r\n"
        assertEquals(HlsPlaylist.Media(11.5, ended = true), HlsPlaylist.parse(vod, "https://cdn.com/a.m3u8"))
        val live = "﻿#EXTM3U\n#EXTINF:4,\nx.ts\n"
        assertEquals(HlsPlaylist.Media(4.0, ended = false), HlsPlaylist.parse(live, "https://cdn.com/a.m3u8"))
        assertNull(HlsPlaylist.parse("<html>Forbidden</html>", "https://cdn.com/a.m3u8"))
        assertNull(HlsPlaylist.parse("", "https://cdn.com/a.m3u8"))
    }

    @Test
    fun aMasterGroupsTheQualitiesItListsWhereverTheyLie() {
        val master = PageVideo("https://a.com/x/master.m3u8", Kind.HLS)
        val quality = PageVideo("https://b.com/y/z/720.m3u8", Kind.HLS)
        val playlists = mutableMapOf<String, HlsPlaylist>()
        val list = PageVideoList<String>(playlists = { playlists[it] })
        list.onFrameVideos("f", listOf(master, quality))
        //the addresses do not tell: both are read
        assertEquals(listOf(master, quality), list.videosOf("f"))
        assertEquals(listOf(master, quality), list.unclearStreams())
        playlists[master.url] = HlsPlaylist.Master(setOf(quality.url))
        assertEquals(listOf(master), list.videosOf("f"))
        assertEquals(listOf(master), list.videos())
        assertEquals(emptyList<PageVideo>(), list.unclearStreams())
    }

    @Test
    fun theDurationOfTheMediaTellsTheStreamItPlays() {
        val film = PageVideo("https://a.com/film/master.m3u8", Kind.HLS)
        val filmQuality = "https://a.com/film/480/index.m3u8"
        val ad = PageVideo("https://ads.com/ad.m3u8", Kind.HLS)
        val frame = listOf(ad, film)
        val playlists = mapOf(
            film.url to HlsPlaylist.Master(setOf(filmQuality, "https://a.com/film/1080/index.m3u8")),
            filmQuality to HlsPlaylist.Media(5537.1, ended = true),
            ad.url to HlsPlaylist.Media(20.0, ended = true))
        val mse = MediaSource(null, mse = true)
        assertEquals(PageVideoMatch(film), PageVideoMatch.of(mse, frame, 5537.125, playlists::get))
        assertEquals(PageVideoMatch(ad), PageVideoMatch.of(mse, frame, 20.0, playlists::get))
        //the choices tell how long their playlists are
        val adOf20 = ad.copy(duration = 20.0)
        val filmOf5537 = film.copy(duration = 5537.1)
        //not known how long the media is: the user picks
        assertEquals(PageVideoMatch(null, listOf(adOf20, filmOf5537)), PageVideoMatch.of(mse, frame, null, playlists::get))
        //a stream of unknown duration may be the one: the one that fits comes first, and is of the same length
        val unknown = PageVideo("https://c.com/other.m3u8", Kind.HLS)
        assertEquals(PageVideoMatch(null, listOf(filmOf5537, adOf20, unknown), sameLength = setOf(film.url)),
            PageVideoMatch.of(mse, frame + unknown, 5537.0, playlists::get))
        //none fits
        assertEquals(PageVideoMatch(null, listOf(adOf20, filmOf5537)), PageVideoMatch.of(mse, frame, 100.0, playlists::get))
    }

    @Test
    fun playlistsAreReadOnceUpToTheLimit() {
        val downloads = mutableListOf<String>()
        var reads = 0
        val playlists = HlsPlaylists(CoroutineScope(Dispatchers.Unconfined), { video ->
            downloads += video.url
            if (video.url.contains("bad")) null else "#EXTM3U\n#EXTINF:10,\na.ts\n#EXT-X-ENDLIST\n"
        }, limit = 3) { reads++ }
        val videos = listOf(PageVideo("https://a.com/1.m3u8", Kind.HLS), PageVideo("https://a.com/f.mp4", Kind.FILE),
            PageVideo("https://a.com/bad.m3u8", Kind.HLS))
        playlists.read(videos)
        playlists.read(videos)
        assertEquals(listOf("https://a.com/1.m3u8", "https://a.com/bad.m3u8"), downloads)
        assertEquals(2, reads)
        assertEquals(HlsPlaylist.Media(10.0, ended = true), playlists["https://a.com/1.m3u8"])
        assertNull(playlists["https://a.com/bad.m3u8"])
        playlists.read((2..5).map { PageVideo("https://a.com/$it.m3u8", Kind.HLS) })
        assertEquals(3, downloads.size)
        playlists.clear()
        assertNull(playlists["https://a.com/1.m3u8"])
    }
}
