package com.phlox.tvwebbrowser.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaUrlIdentityTest {
    @Test fun collapsesRotatedSignaturesAndExpiry() {
        val a = "https://hls.example/secure.php?path=ab12%2Findex.m3u8&e=1&sig=AAA"
        val b = "https://hls.example/secure.php?path=ab12%2Findex.m3u8&e=2&sig=BBB&v=9-1"
        val c = "https://hls.example/secure.php?path=ab12%2Findex.m3u8&e=3&sig=CCC"
        assertEquals(MediaUrlIdentity.key(a), MediaUrlIdentity.key(b))
        assertEquals(MediaUrlIdentity.key(b), MediaUrlIdentity.key(c))
    }

    @Test fun keepsDistinctResourcesApart() {
        val a = "https://hls.example/secure.php?path=ab12%2Findex.m3u8&sig=AAA"
        val b = "https://hls.example/secure.php?path=cd34%2Findex.m3u8&sig=AAA"
        assertNotEquals(MediaUrlIdentity.key(a), MediaUrlIdentity.key(b))
    }

    @Test fun plainManifestWithoutQuery() {
        assertEquals("cdn.example/v/master.m3u8", MediaUrlIdentity.key("https://cdn.example/v/master.m3u8"))
    }

    @Test fun queryWithoutResourceParamKeptVerbatim() {
        val a = "https://cdn.example/v/master.m3u8?token=abc"
        val b = "https://cdn.example/v/master.m3u8?token=def"
        assertNotEquals(MediaUrlIdentity.key(a), MediaUrlIdentity.key(b))
        assertEquals(MediaUrlIdentity.key(a), MediaUrlIdentity.key("https://cdn.example/v/master.m3u8?token=abc"))
    }

    @Test fun keepsProgressiveQueryVariantsApart() {
        val low = "https://cdn.example/v.mp4?quality=low"
        val high = "https://cdn.example/v.mp4?quality=high"
        assertNotEquals(MediaUrlIdentity.key(low), MediaUrlIdentity.key(high))
    }
}
