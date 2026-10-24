package com.phlox.tvwebbrowser.adblock

import com.phlox.tvwebbrowser.adblock.ResourceType.DOCUMENT
import com.phlox.tvwebbrowser.adblock.ResourceType.FONT
import com.phlox.tvwebbrowser.adblock.ResourceType.IMAGE
import com.phlox.tvwebbrowser.adblock.ResourceType.MEDIA
import com.phlox.tvwebbrowser.adblock.ResourceType.OTHER
import com.phlox.tvwebbrowser.adblock.ResourceType.PING
import com.phlox.tvwebbrowser.adblock.ResourceType.SCRIPT
import com.phlox.tvwebbrowser.adblock.ResourceType.STYLESHEET
import com.phlox.tvwebbrowser.adblock.ResourceType.SUBDOCUMENT
import com.phlox.tvwebbrowser.adblock.ResourceType.XHR
import com.phlox.tvwebbrowser.adblock.ResourceType.guessFromAccept
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceTypeTest {
    @Test
    fun acceptHeadersOfChromium() {
        assertEquals(IMAGE, guessFromAccept("image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8", "https://x/a", false))
        assertEquals(STYLESHEET, guessFromAccept("text/css,*/*;q=0.1", "https://x/a", false))
        assertEquals(SUBDOCUMENT, guessFromAccept("text/html,application/xhtml+xml,application/xml;q=0.9", "https://x/a", false))
        assertEquals(DOCUMENT, guessFromAccept("text/html", "https://x/", true))
    }

    @Test
    fun anyTypeIsNarrowedByTheExtension() {
        assertEquals(SCRIPT, guessFromAccept("*/*", "https://x/a/b.js?v=1", false))
        assertEquals(FONT, guessFromAccept("*/*", "https://x/f.woff2", false))
        assertEquals(MEDIA, guessFromAccept(null, "https://x/v/master.m3u8#t", false))
        assertEquals(IMAGE, guessFromAccept("*/*", "https://x/p.PNG", false))
    }

    @Test
    fun anyTypeWithoutExtensionIsNeverAPing() {
        val types = guessFromAccept("*/*", "https://x/collect?id=1", false)
        assertEquals(SCRIPT or XHR or OTHER, types)
        assertFalse(types and PING != 0)
        //a dot in the host is not an extension
        assertEquals(SCRIPT or XHR or OTHER, guessFromAccept("*/*", "https://cdn.example.com", false))
    }

    @Test
    fun webExtensionTypes() {
        assertEquals(DOCUMENT, ResourceType.fromWebExtension("main_frame"))
        assertEquals(SUBDOCUMENT, ResourceType.fromWebExtension("sub_frame"))
        assertEquals(PING, ResourceType.fromWebExtension("beacon"))
        assertEquals(OTHER, ResourceType.fromWebExtension("speculative"))
    }

    @Test
    fun enginesKnowWhetherFiltersBlockPages() {
        assertTrue(TestPsl.compile("||bad.example^\$document").blocksPages)
        assertFalse(TestPsl.compile("||ads.example.net^", "@@||site.com^\$document").blocksPages)
    }
}
