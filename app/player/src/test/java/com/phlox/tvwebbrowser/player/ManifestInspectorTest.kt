package com.phlox.tvwebbrowser.player

import org.junit.Assert.*
import org.junit.Test

class ManifestInspectorTest {
    @Test fun extractsMasterChildrenIncludingAudioButNotSegments() {
        val info = ManifestInspector.inspect("https://cdn.example/v/master.m3u8", """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="audio/list.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000
            high/list.m3u8
            #EXTINF:10,
            segment.ts
        """.trimIndent(), MediaRequestCatalog.Kind.HLS)
        assertEquals(setOf("https://cdn.example/v/audio/list.m3u8", "https://cdn.example/v/high/list.m3u8"), info.children)
        assertFalse(info.protectedContent)
    }
    @Test fun aes128IsSupportedButDrmStaysWithWebsite() {
        assertFalse(ManifestInspector.inspect("https://a.test/x", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"", MediaRequestCatalog.Kind.HLS).protectedContent)
        assertTrue(ManifestInspector.inspect("https://a.test/x", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd:key\"", MediaRequestCatalog.Kind.HLS).protectedContent)
        assertTrue(ManifestInspector.inspect("https://a.test/x", "<MPD><cenc:ContentProtection/></MPD>", MediaRequestCatalog.Kind.DASH).protectedContent)
    }
}