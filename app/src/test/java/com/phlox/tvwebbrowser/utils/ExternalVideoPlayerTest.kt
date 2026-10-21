package com.phlox.tvwebbrowser.utils

import android.content.Intent
import android.provider.Browser
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.PageVideo.Kind
import com.phlox.tvwebbrowser.webengine.PageVideoReports
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalVideoPlayerTest {
    private val video = PageVideo("https://cdn.example.com/hls/master.m3u8?t=1", Kind.HLS, title = "A film",
        pageUrl = "https://player.example.com/embed/42", userAgent = "UA/1.0")

    @Test
    fun theIntentHasTheTypeTheTitleAndTheHeaders() {
        val intent = ExternalVideoPlayer.intent(video, "a=1; b=2")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(video.url, intent.dataString)
        assertEquals("application/x-mpegURL", intent.type)
        assertEquals("A film", intent.getStringExtra("title"))
        val bundle = intent.getBundleExtra(Browser.EXTRA_HEADERS)!!
        assertEquals("https://player.example.com/embed/42", bundle.getString("Referer"))
        assertEquals("UA/1.0", bundle.getString("User-Agent"))
        assertEquals("a=1; b=2", bundle.getString("Cookie"))
        assertArrayEquals(arrayOf("Referer", "https://player.example.com/embed/42", "User-Agent", "UA/1.0", "Cookie", "a=1; b=2"),
            intent.getStringArrayExtra("headers"))
    }

    @Test
    fun withoutHeadersThereAreNoHeaderExtras() {
        val intent = ExternalVideoPlayer.intent(PageVideo("https://a.com/v/movie.mp4", Kind.FILE), null)
        assertEquals("video/mp4", intent.type)
        assertEquals("movie.mp4", intent.getStringExtra("title"))
        assertFalse(intent.hasExtra(Browser.EXTRA_HEADERS))
        assertFalse(intent.hasExtra("headers"))
    }

    @Test
    fun reportsOfTheScriptBecomeVideos() {
        val videos = PageVideoReports.parse(JSONObject("""{"videos":[
            {"url":"https://a.com/movie.mp4","element":true,"width":1920,"height":1080,"duration":61.5},
            {"url":"https://cdn.com/live/index.m3u8","element":false},
            {"url":"https://cdn.com/seg-1.ts","element":false},
            {"url":"blob:https://a.com/1","element":true}],
            "title":"Page","page":"https://a.com/watch","ua":"UA"}"""))
        assertEquals(listOf("https://a.com/movie.mp4", "https://cdn.com/live/index.m3u8"), videos.map { it.url })
        assertEquals(PageVideo("https://a.com/movie.mp4", Kind.FILE, "Page", "https://a.com/watch", "UA", 1920, 1080, 61.5),
            videos[0])
        assertNull(videos[1].width)
        assertEquals(emptyList<PageVideo>(), PageVideoReports.parse(JSONObject("""{"videos":[]}""")))
    }
}
