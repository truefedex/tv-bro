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
    @Test fun extractsHlsResolutionBandwidthAndLanguages() {
        val info = ManifestInspector.inspect("https://cdn.example/v/master.m3u8", """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="French",LANGUAGE="fr",URI="audio/fr.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
            low/list.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
            high/list.m3u8
        """.trimIndent(), MediaRequestCatalog.Kind.HLS)
        assertEquals(1920, info.metadata.width)
        assertEquals(1080, info.metadata.height)
        assertEquals(2500000L, info.metadata.bandwidth)
        assertEquals(listOf("en", "fr"), info.metadata.languages)
        assertEquals("1920×1080", info.metadata.resolution)
    }
    @Test fun extractsDashMetadata() {
        val info = ManifestInspector.inspect("https://cdn.example/v/manifest.mpd", """
            <MPD mediaPresentationDuration="PT1H2M3S">
              <AdaptationSet lang="en">
                <Representation width="1920" height="1080" bandwidth="5000000"/>
              </AdaptationSet>
            </MPD>
        """.trimIndent(), MediaRequestCatalog.Kind.DASH)
        assertEquals(1920, info.metadata.width)
        assertEquals(1080, info.metadata.height)
        assertEquals(listOf("en"), info.metadata.languages)
        assertEquals(5000000L, info.metadata.bandwidth)
        assertEquals(3723000L, info.metadata.durationMs)
    }
}