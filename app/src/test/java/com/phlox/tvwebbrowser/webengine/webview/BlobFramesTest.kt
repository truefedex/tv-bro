package com.phlox.tvwebbrowser.webengine.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlobFramesTest {
    @Test
    fun originOfBlobUrl() {
        assertEquals("https://site.com", BlobFrames.originOf("blob:https://site.com/0d5c6c4e-7c1a"))
        assertEquals("http://localhost:8765", BlobFrames.originOf("blob:http://localhost:8765/abc"))
        assertEquals("https://site.com", BlobFrames.originOf("BLOB:HTTPS://Site.com/abc"))
        assertEquals("null", BlobFrames.originOf("blob:null/abc"))
        assertNull(BlobFrames.originOf("https://site.com/a.txt"))
    }

    @Test
    fun mainFramesOfTheOriginComeFirstNewestFirst() {
        val frames = BlobFrames<String>()
        frames.onHello("https://site.com", false, "sub1")
        frames.onHello("https://site.com", true, "main1")
        frames.onHello("https://other.com", true, "other")
        frames.onHello("https://site.com", false, "sub2")
        frames.onHello("https://site.com", true, "main2")
        assertEquals(listOf("main2", "main1", "sub2", "sub1"), frames.candidates("https://site.com"))
        assertEquals(listOf("other"), frames.candidates("https://OTHER.com"))
        assertEquals(emptyList<String>(), frames.candidates("https://none.com"))
    }

    @Test
    fun aFrameSayingHelloAgainMovesToTheNewestOrigin() {
        //a frame whose document went to another origin
        val frames = BlobFrames<String>()
        frames.onHello("https://site.com", true, "main")
        frames.onHello("https://other.com", true, "main")
        assertEquals(emptyList<String>(), frames.candidates("https://site.com"))
        assertEquals(listOf("main"), frames.candidates("https://other.com"))
    }

    @Test
    fun theOldestFramesAreDropped() {
        val frames = BlobFrames<Int>(max = 3)
        for (i in 1..5) frames.onHello("https://site.com", false, i)
        assertEquals(listOf(5, 4, 3), frames.candidates("https://site.com"))
    }
}
